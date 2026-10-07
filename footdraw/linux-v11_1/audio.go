package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"io"
	"net"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type MicDevice struct {
	Name  string
	Label string
}

type Microphone struct {
	mu       sync.Mutex
	devices  []MicDevice
	selected int
	muted    bool
	cancel   context.CancelFunc
	seq      atomic.Uint32
	redraw   func()
}

func NewMicrophone(redraw func()) *Microphone {
	m := &Microphone{redraw: redraw}
	m.RefreshDevices()
	return m
}

func (m *Microphone) RefreshDevices() {
	devs := []MicDevice{{Name: "", Label: "Default"}}
	cmd := exec.Command("pactl", "list", "short", "sources")
	out, err := cmd.Output()
	if err == nil {
		sc := bufio.NewScanner(strings.NewReader(string(out)))
		for sc.Scan() {
			f := strings.Fields(sc.Text())
			if len(f) < 2 {
				continue
			}
			name := f[1]
			label := name
			if len(label) > 42 {
				label = label[:39] + "..."
			}
			devs = append(devs, MicDevice{Name: name, Label: label})
		}
	}
	m.mu.Lock()
	oldName := ""
	if m.selected >= 0 && m.selected < len(m.devices) {
		oldName = m.devices[m.selected].Name
	}
	m.devices = devs
	m.selected = 0
	for i := range devs {
		if devs[i].Name == oldName {
			m.selected = i
			break
		}
	}
	m.mu.Unlock()
}

func (m *Microphone) Devices() ([]MicDevice, int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := append([]MicDevice(nil), m.devices...)
	return out, m.selected
}

func (m *Microphone) Muted() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.muted
}

func (m *Microphone) ToggleMute() {
	m.mu.Lock()
	m.muted = !m.muted
	m.mu.Unlock()
	m.redraw()
}

func (m *Microphone) Start() {
	m.mu.Lock()
	idx := m.selected
	m.mu.Unlock()
	m.Select(idx)
}

func (m *Microphone) Close() {
	m.mu.Lock()
	if m.cancel != nil {
		m.cancel()
		m.cancel = nil
	}
	m.mu.Unlock()
}

func (m *Microphone) Select(index int) {
	m.RefreshDevices()
	m.mu.Lock()
	if index < 0 || index >= len(m.devices) {
		index = 0
	}
	if m.cancel != nil {
		m.cancel()
		m.cancel = nil
	}
	m.selected = index
	device := m.devices[index].Name
	ctx, cancel := context.WithCancel(context.Background())
	m.cancel = cancel
	m.mu.Unlock()
	go m.captureSupervisor(ctx, device)
	m.redraw()
}

func (m *Microphone) captureSupervisor(ctx context.Context, device string) {
	for ctx.Err() == nil {
		if err := m.captureOnce(ctx, device); err != nil {
			select {
			case <-time.After(350 * time.Millisecond):
			case <-ctx.Done():
				return
			}
		}
	}
}

func (m *Microphone) captureOnce(ctx context.Context, device string) error {
	args := []string{"--raw", "--format=s16le", "--rate=16000", "--channels=1"}
	if device != "" {
		args = append(args, "--device="+device)
	}
	cmd := exec.CommandContext(ctx, "parec", args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	defer func() {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		_ = cmd.Wait()
	}()

	addr, err := net.ResolveUDPAddr("udp", serverAddr)
	if err != nil {
		return err
	}
	conn, err := net.DialUDP("udp", nil, addr)
	if err != nil {
		return err
	}
	defer conn.Close()
	_ = conn.SetWriteBuffer(256 * 1024)

	const frameBytes = 640
	frame := make([]byte, frameBytes)
	var prev []byte
	for ctx.Err() == nil {
		if _, err := io.ReadFull(stdout, frame); err != nil {
			return err
		}
		cur := append([]byte(nil), frame...)
		m.mu.Lock()
		muted := m.muted
		m.mu.Unlock()
		if muted {
			prev = nil
			continue
		}

		prevLen := 0
		if len(prev) == frameBytes {
			prevLen = frameBytes
		}
		inner := make([]byte, 4+2+frameBytes+2+prevLen)
		copy(inner[:4], []byte("FDR2"))
		binary.BigEndian.PutUint16(inner[4:6], uint16(frameBytes))
		copy(inner[6:6+frameBytes], cur)
		o := 6 + frameBytes
		binary.BigEndian.PutUint16(inner[o:o+2], uint16(prevLen))
		if prevLen != 0 {
			copy(inner[o+2:], prev)
		}

		seq := m.seq.Add(1)
		pkt := make([]byte, 42+len(inner))
		copy(pkt[:4], []byte("FDO1"))
		copy(pkt[4:36], []byte(sharedToken))
		binary.BigEndian.PutUint32(pkt[36:40], seq)
		binary.BigEndian.PutUint16(pkt[40:42], uint16(len(inner)/2))
		copy(pkt[42:], inner)
		if _, err := conn.Write(pkt); err != nil {
			return err
		}
		prev = cur
	}
	return ctx.Err()
}
