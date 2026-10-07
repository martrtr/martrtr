package main

import (
	"bufio"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	serverAddr  = "31.77.251.51:4950"
	sharedToken = "f2f3a025173941f9cb1d297eba2c0469"
)

type Network struct {
	state  *AppState
	redraw func()
	cmdCh  chan string
	stopCh chan struct{}
	once   sync.Once
}

func NewNetwork(state *AppState, redraw func()) *Network {
	return &Network{
		state: state, redraw: redraw,
		cmdCh: make(chan string, 256),
		stopCh: make(chan struct{}),
	}
}

func (n *Network) Start() { go n.loop() }

func (n *Network) Close() {
	n.once.Do(func() { close(n.stopCh) })
}

func (n *Network) Send(line string) {
	select {
	case n.cmdCh <- line:
	default:
		go func() {
			select {
			case n.cmdCh <- line:
			case <-n.stopCh:
			}
		}()
	}
}

func (n *Network) SendView() {
	s := n.state.snapshot()
	n.Send(fmt.Sprintf("VIEW %.12g %.12g", s.PhoneVX, s.PhoneVY))
}

func (n *Network) SendBG(c uint32)    { n.Send(fmt.Sprintf("BG %06x", c&0xFFFFFF)) }
func (n *Network) SendBrush(c uint32) { n.Send(fmt.Sprintf("BRUSH %06x", c&0xFFFFFF)) }
func (n *Network) SendOrient(land bool) {
	if land {
		n.Send("ORIENT 1")
	} else {
		n.Send("ORIENT 0")
	}
}
func (n *Network) SendClip(enabled bool, l, t, r, b float64) {
	v := 0
	if enabled {
		v = 1
	}
	n.Send(fmt.Sprintf("CLIP %d %.7f %.7f %.7f %.7f", v, l, t, r, b))
}
func (n *Network) SendMode(gyro bool) {
	if gyro {
		n.Send("MODE 1")
	} else {
		n.Send("MODE 0")
	}
}
func (n *Network) SendGyroView() {
	cx, cy, wpp := n.state.gyroMapping()
	n.Send(fmt.Sprintf("GVIEW %.12g %.12g %.12g", cx, cy, wpp))
}

func (n *Network) loop() {
	backoff := 150 * time.Millisecond
	for {
		select {
		case <-n.stopCh:
			return
		default:
		}
		conn, err := net.DialTimeout("tcp", serverAddr, 2500*time.Millisecond)
		if err != nil {
			n.state.setConnected(false)
			n.redraw()
			select {
			case <-time.After(backoff):
			case <-n.stopCh:
				return
			}
			if backoff < 2500*time.Millisecond {
				backoff *= 2
			}
			continue
		}
		_ = conn.(*net.TCPConn).SetNoDelay(true)
		_ = conn.(*net.TCPConn).SetKeepAlive(true)
		backoff = 150 * time.Millisecond
		if err := n.runConn(conn); err != nil {
			_ = conn.Close()
		}
		n.state.setConnected(false)
		n.redraw()
	}
}

func (n *Network) runConn(conn net.Conn) error {
	defer conn.Close()
	writer := bufio.NewWriterSize(conn, 8192)
	write := func(s string) error {
		_ = conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
		if _, err := writer.WriteString(s + "\n"); err != nil {
			return err
		}
		return writer.Flush()
	}
	if err := write("HELLO OP " + sharedToken + " linux-v11.1 0 0"); err != nil {
		return err
	}
	n.state.setConnected(true)
	n.redraw()

	lines := make(chan string, 128)
	readErr := make(chan error, 1)
	go func() {
		sc := bufio.NewScanner(conn)
		buf := make([]byte, 4096)
		sc.Buffer(buf, 1<<20)
		for sc.Scan() {
			select {
			case lines <- sc.Text():
			case <-n.stopCh:
				return
			}
		}
		if err := sc.Err(); err != nil {
			readErr <- err
		} else {
			readErr <- fmt.Errorf("server closed")
		}
	}()

	ping := time.NewTicker(2 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-n.stopCh:
			return nil
		case err := <-readErr:
			return err
		case line := <-lines:
			n.handleLine(line)
		case cmd := <-n.cmdCh:
			if err := write(cmd); err != nil {
				return err
			}
		case <-ping.C:
			if err := write("PING"); err != nil {
				return err
			}
		}
	}
}

func parseHex(s string, fallback uint32) uint32 {
	v, err := strconv.ParseUint(s, 16, 32)
	if err != nil {
		return fallback
	}
	return uint32(v) & 0xFFFFFF
}

func parseF(s string, fallback float64) float64 {
	v, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return fallback
	}
	return v
}

func parseI64(s string, fallback int64) int64 {
	v, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return fallback
	}
	return v
}

func parseInt(s string, fallback int) int {
	v, err := strconv.Atoi(s)
	if err != nil {
		return fallback
	}
	return v
}

func (n *Network) handleLine(line string) {
	a := strings.Fields(strings.TrimSpace(line))
	if len(a) == 0 {
		return
	}
	s := n.state
	switch a[0] {
	case "PONG", "SYNCED":
		return
	case "STATE":
		if len(a) < 6 {
			return
		}
		e := parseI64(a[1], 1)
		s.mu.Lock()
		if s.epoch != e {
			s.events = nil
			s.epoch = e
		}
		s.phoneVX = parseF(a[2], s.phoneVX)
		s.phoneVY = parseF(a[3], s.phoneVY)
		s.phoneW = parseInt(a[4], s.phoneW)
		s.phoneH = parseInt(a[5], s.phoneH)
		ww, wh := worldDims(s.landscape, s.phoneW, s.phoneH)
		s.gyroCX = s.phoneVX + ww/2
		s.gyroCY = s.phoneVY + wh/2
		s.mu.Unlock()
	case "CFG":
		if len(a) < 9 {
			return
		}
		s.mu.Lock()
		s.bgColor = parseHex(a[1], s.bgColor)
		s.brushColor = parseHex(a[2], s.brushColor)
		s.landscape = a[3] != "0"
		s.clip = a[4] != "0"
		s.clipL = clamp(parseF(a[5], s.clipL), 0, 1)
		s.clipT = clamp(parseF(a[6], s.clipT), 0, 1)
		s.clipR = clamp(parseF(a[7], s.clipR), s.clipL, 1)
		s.clipB = clamp(parseF(a[8], s.clipB), s.clipT, 1)
		s.mu.Unlock()
	case "MODE":
		if len(a) < 2 {
			return
		}
		s.mu.Lock()
		s.gyroMode = a[1] != "0"
		if !s.gyroMode {
			s.gyroDX, s.gyroDY, s.gyroDown = 0, 0, false
		}
		s.mu.Unlock()
	case "GVIEW":
		if len(a) < 4 {
			return
		}
		s.mu.Lock()
		s.gyroCX = parseF(a[1], s.gyroCX)
		s.gyroCY = parseF(a[2], s.gyroCY)
		wpp := parseF(a[3], s.gyroWpp)
		if wpp > 0 {
			s.gyroWpp = wpp
		}
		s.mu.Unlock()
	case "GC":
		if len(a) < 4 {
			return
		}
		s.mu.Lock()
		s.gyroDX = parseF(a[1], 0)
		s.gyroDY = parseF(a[2], 0)
		s.gyroDown = a[3] != "0"
		s.mu.Unlock()
	case "RESET":
		if len(a) < 2 {
			return
		}
		e := parseI64(a[1], s.epoch)
		s.mu.Lock()
		s.epoch = e
		s.events = nil
		s.mu.Unlock()
	case "O":
		if len(a) < 9 {
			return
		}
		ev := DrawEvent{
			GSeq: parseI64(a[1], 0), Epoch: parseI64(a[2], 0),
			Stroke: a[3], Kind: a[4],
			X: parseF(a[5], 0), Y: parseF(a[6], 0), P: parseF(a[7], 1),
			Color: parseHex(a[8], 0xEBEEF4),
		}
		s.mu.Lock()
		if ev.Epoch == s.epoch {
			s.events = append(s.events, ev)
		}
		s.mu.Unlock()
	}
	n.redraw()
}
