"""live-check.sh 보조 도구. 표준 라이브러리만 쓴다.

  totp <base32 비밀값>          지금 TOTP 코드
  new-secret                    새 base32 TOTP 비밀값 (160비트)
  sse <대화 id 저장 파일>        표준 입력의 SSE를 읽어 요약 출력
  cost                          표준 입력의 앱 로그에서 claude.usage 줄을 모아 비용 집계
  cost-total                    cost와 같지만 총액(달러)만 출력
"""
import base64
import hashlib
import hmac
import json
import os
import re
import struct
import sys
import time

# Claude Sonnet 5.5 정가 (달러 / 100만 토큰), 웹 검색은 1,000회당 10달러
PRICE_INPUT = 2.0
PRICE_OUTPUT = 10.0
PRICE_CACHE_READ = 0.20
PRICE_CACHE_WRITE = 2.50
PRICE_WEB_SEARCH = 0.01


def totp(secret: str) -> str:
    key = base64.b32decode(secret.upper() + "=" * (-len(secret) % 8))
    digest = hmac.new(key, struct.pack(">Q", int(time.time()) // 30), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000
    return f"{code:06d}"


def sse(conv_file: str) -> None:
    """이벤트 이름별로 요약한다. delta는 이어 붙여 답변 전체를 보여 준다"""
    event, text, others = None, [], []
    for raw in sys.stdin:
        line = raw.rstrip("\n")
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            try:
                data = json.loads(line[5:].strip())
            except json.JSONDecodeError:
                others.append(f"[{event}] (JSON 아님) {line[5:].strip()}")
                continue
            if event == "meta":
                with open(conv_file, "w") as f:
                    f.write(str(data["conversationId"]))
            elif event == "delta":
                text.append(data.get("text", ""))
            else:
                others.append(f"[{event}] {json.dumps(data, ensure_ascii=False)}")
    print("  답변:")
    for line in "".join(text).strip().splitlines() or ["(빈 답변)"]:
        print("    " + line)
    for o in others:
        print("  " + o)


USAGE = re.compile(r"claude call (.*)$")


def calls(log: str):
    for line in log.splitlines():
        m = USAGE.search(line)
        if not m:
            continue
        fields = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv)
        yield fields


def num(fields, key) -> int:
    v = fields.get(key, "-")
    return int(v) if v.isdigit() else 0


def call_cost(f) -> float:
    return (
        num(f, "input_tokens") * PRICE_INPUT
        + num(f, "output_tokens") * PRICE_OUTPUT
        + num(f, "cache_read_tokens") * PRICE_CACHE_READ
        + num(f, "cache_creation_tokens") * PRICE_CACHE_WRITE
    ) / 1_000_000 + num(f, "web_search_requests") * PRICE_WEB_SEARCH


def cost(total_only: bool) -> None:
    rows = list(calls(sys.stdin.read()))
    total = sum(call_cost(f) for f in rows)
    if total_only:
        print(f"{total:.4f}")
        return
    print(f"  {'kind':<8} {'stop':<10} {'in':>7} {'out':>6} {'c_read':>7} {'c_write':>7} {'search':>6} {'tools':>5} {'ms':>6} {'$':>7}  error")
    for f in rows:
        print(
            f"  {f.get('kind', '-'):<8} {f.get('stop_reason', '-'):<10} {num(f, 'input_tokens'):>7} {num(f, 'output_tokens'):>6}"
            f" {num(f, 'cache_read_tokens'):>7} {num(f, 'cache_creation_tokens'):>7} {num(f, 'web_search_requests'):>6}"
            f" {f.get('tool_calls', '-'):>5} {num(f, 'duration_ms'):>6} {call_cost(f):>7.4f}  {f.get('error', '-')}"
        )
    by_kind = {}
    for f in rows:
        by_kind[f.get("kind", "-")] = by_kind.get(f.get("kind", "-"), 0) + call_cost(f)
    print("  분류별: " + ", ".join(f"{k} ${v:.4f}" for k, v in sorted(by_kind.items())))
    print(f"  호출 {len(rows)}회, 합계 ${total:.4f} (정가 기준 추정)")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "totp":
        print(totp(sys.argv[2]))
    elif cmd == "new-secret":
        print(base64.b32encode(os.urandom(20)).decode().rstrip("="))
    elif cmd == "sse":
        sse(sys.argv[2])
    elif cmd == "cost":
        cost(False)
    elif cmd == "cost-total":
        cost(True)
    else:
        sys.exit(f"unknown command: {cmd}")
