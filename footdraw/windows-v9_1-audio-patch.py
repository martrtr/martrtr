from pathlib import Path

p=Path('net.go')
s=p.read_text()

start=s.index('func audioSenderLoop')
# audioSenderLoop is the last function in net.go in the v7/v8 source. Replace it whole.
new=r'''func audioSenderLoop(ch <-chan []byte) {
	const frameBytes = 640 // 20 ms @ 16 kHz mono PCM16
	addr, _ := net.ResolveUDPAddr("udp", serverAddr)
	var prev []byte
	var accum []byte
	for {
		c, err := net.DialUDP("udp", nil, addr)
		if err != nil {
			time.Sleep(300 * time.Millisecond)
			continue
		}
		_ = c.SetWriteBuffer(256 * 1024)
		for block := range ch {
			if len(block) == 0 {
				continue
			}
			if isMicMuted() {
				accum = accum[:0]
				prev = nil
				continue
			}
			accum = append(accum, block...)
			for len(accum) >= frameBytes {
				cur := append([]byte(nil), accum[:frameBytes]...)
				accum = accum[frameBytes:]

				// FDR2 is an application-level redundancy wrapper. The server does
				// not need to understand it; it relays the payload unchanged. Every
				// packet contains the current 20 ms frame and the previous one, so a
				// single lost UDP datagram is recovered by the next datagram.
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

				seq := atomic.AddUint32(&audioSeq, 1)
				pkt := make([]byte, 42+len(inner))
				copy(pkt[:4], []byte("FDO1"))
				copy(pkt[4:36], []byte(sharedToken))
				binary.BigEndian.PutUint32(pkt[36:40], seq)
				binary.BigEndian.PutUint16(pkt[40:42], uint16(len(inner)/2))
				copy(pkt[42:], inner)
				if _, err = c.Write(pkt); err != nil {
					_ = c.Close()
					break
				}
				prev = cur
			}
			if err != nil {
				break
			}
		}
	}
}
'''
s=s[:start]+new
p.write_text(s)
