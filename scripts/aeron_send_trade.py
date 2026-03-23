#!/usr/bin/env python3
"""
Send one Aeron trade frame (little-endian) to UDP localhost:40123.
Wire format matches README / AeronTradeSubscriber.
"""
import argparse
import socket
import struct
import sys


def build_frame(user_id: int, volume: float, timestamp_ms: int, symbol: str) -> bytes:
    sym = symbol.encode("utf-8")
    head = struct.pack("<qdqi", user_id, volume, timestamp_ms, len(sym))
    return head + sym


def main() -> int:
    p = argparse.ArgumentParser(description="Send a single Aeron-style trade datagram")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=40123)
    p.add_argument("--user-id", type=int, default=2001)
    p.add_argument("--volume", type=float, default=3.5)
    p.add_argument("--symbol", default="AERON")
    p.add_argument("--timestamp-ms", type=int, default=None)
    args = p.parse_args()
    import time

    ts = args.timestamp_ms if args.timestamp_ms is not None else int(time.time() * 1000)
    payload = build_frame(args.user_id, args.volume, ts, args.symbol)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.sendto(payload, (args.host, args.port))
        print(f"Sent {len(payload)} bytes to udp://{args.host}:{args.port} userId={args.user_id}")
    finally:
        sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
