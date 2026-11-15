"""DRIVE VILLAGE 효과음 생성기(파이썬 표준 라이브러리만 사용).

모든 효과음은 이 스크립트가 사인파·종소리 배음·짧은 저역 노이즈를 합성해 만든 것이다.
외부 음원·샘플·AI 생성물은 쓰지 않았다(팀 자체 저작물). 같은 입력이면 같은 파일이 나온다(난수 시드 고정).

실행: python tools/gen_sfx.py   (저장소 루트에서)
출력: app/src/main/res/raw/sfx_<이름>_<번호>.wav  (22050Hz, 모노, 16비트 PCM)

설계 기준(근거 등급은 연구 노트 05 참조, 모두 경험칙·설계 제안이며 검증된 임계값이 아니다):
- 길이 0.1~0.8초, 어택을 완만하게(2~4ms), 끝(10ms 또는 길이의 12% 중 긴 쪽)은 0으로 페이드(클릭 잡음 방지).
- 반복되는 클릭류는 피치를 약 3% 다르게 한 변형 2~3종을 두고 앱이 돌려 가며 쓴다.
- 고주파 날카로움을 피하려고 기본음은 200Hz~1.6kHz, 배음은 나이퀴스트(11kHz) 아래로 둔다.
- 기본 볼륨을 낮게: 최대 진폭을 -12dBFS(0.25)로 맞춘다. 앱은 여기에 재생 볼륨을 한 번 더 곱한다.

길이(ms)를 바꾸면 앱의 Sfx.durationMs도 같이 바꿔야 한다(SoundRulesTest.synthesizedAssetsMatchSfxTable이 확인한다).
"""
import math
import os
import random
import struct
import wave

RATE = 22050
PEAK = 0.25
FADE_S = 0.010

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "raw")

# 종소리 배음(비율, 진폭, 감쇠 시간 비율). 비율 2.76·5.4는 금속 종의 비정수 배음을 흉내 낸 값.
BELL = [(1.0, 1.0, 1.0), (2.0, 0.35, 0.55), (2.76, 0.18, 0.35), (5.4, 0.06, 0.15)]


def blank(dur):
    return [0.0] * int(RATE * dur)


def add_tone(buf, start, freq, partials, tau, amp=1.0, attack=0.003):
    """buf에 start초부터 배음 음 하나를 더한다. partials = [(비율, 진폭, 감쇠비)]."""
    s0 = int(start * RATE)
    for i in range(s0, len(buf)):
        t = (i - s0) / RATE
        env = min(1.0, t / attack) if attack > 0 else 1.0
        v = 0.0
        for ratio, a, tr in partials:
            v += a * math.exp(-t / (tau * tr)) * math.sin(2 * math.pi * freq * ratio * t)
        buf[i] += amp * env * v


def add_noise(buf, start, dur, amp, seed, smooth=0.15):
    """저역으로 걸러 부드럽게 만든 짧은 노이즈(나무·천에 놓는 느낌)."""
    rnd = random.Random(seed)
    s0 = int(start * RATE)
    n = int(dur * RATE)
    y = 0.0
    for k in range(n):
        if s0 + k >= len(buf):
            break
        y += smooth * (rnd.uniform(-1, 1) - y)
        env = math.exp(-k / (n * 0.3))
        buf[s0 + k] += amp * env * y


def finish(buf):
    peak = max(abs(v) for v in buf) or 1.0
    out = [v / peak * PEAK for v in buf]
    fade = max(int(FADE_S * RATE), int(len(out) * 0.12))  # 긴 종소리는 꼬리를 더 길게 줄인다
    for k in range(fade):
        out[len(out) - 1 - k] *= k / fade
    out[0] = 0.0
    return out


def write(name, buf):
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in buf))
    return path


def tap(freq):
    b = blank(0.100)
    add_tone(b, 0, freq, [(1.0, 1.0, 1.0), (2.0, 0.25, 0.55)], tau=0.018, attack=0.002)
    return finish(b)


def two_note(f1, f2, gain=1.0):
    b = blank(0.160)
    add_tone(b, 0.000, f1, [(1.0, 1.0, 1.0), (2.0, 0.2, 0.5)], tau=0.030, amp=gain)
    add_tone(b, 0.060, f2, [(1.0, 1.0, 1.0), (2.0, 0.2, 0.5)], tau=0.040, amp=gain)
    return finish(b)


def place(freq, seed):
    b = blank(0.200)
    add_tone(b, 0, freq, [(1.0, 1.0, 1.0), (2.0, 0.4, 0.6), (3.1, 0.15, 0.4)], tau=0.050, attack=0.002)
    add_noise(b, 0, 0.030, 0.35, seed)
    return finish(b)


def light():
    b = blank(0.700)
    for i, f in enumerate([1046.5, 1318.5, 1568.0]):  # C6 E6 G6 위로 퍼지는 종소리
        add_tone(b, 0.09 * i, f, BELL, tau=0.30, amp=1.0 - 0.15 * i, attack=0.004)
    return finish(b)


def chime():
    b = blank(0.800)
    add_tone(b, 0.000, 110.0, [(1.0, 1.0, 1.0)], tau=0.040, amp=0.6, attack=0.002)  # 도장이 닿는 부드러운 '톡'
    add_tone(b, 0.020, 784.0, BELL, tau=0.40, amp=0.9, attack=0.004)   # G5
    add_tone(b, 0.140, 1046.5, BELL, tau=0.40, amp=0.8, attack=0.004)  # C6
    return finish(b)


def main():
    os.makedirs(OUT, exist_ok=True)
    made = []
    for i, f in enumerate([640.0, 660.0, 680.0], start=1):  # 약 3%씩 다른 피치
        made.append(write(f"sfx_tap_{i}", tap(f)))
    for i, k in enumerate([1.0, 1.03], start=1):
        made.append(write(f"sfx_select_{i}", two_note(659.3 * k, 880.0 * k)))
    for i, k in enumerate([1.0, 0.97], start=1):
        made.append(write(f"sfx_cancel_{i}", two_note(659.3 * k, 523.3 * k, gain=0.8)))
    for i, (f, seed) in enumerate([(196.0, 11), (208.0, 12)], start=1):
        made.append(write(f"sfx_place_{i}", place(f, seed)))
    made.append(write("sfx_light_1", light()))
    made.append(write("sfx_chime_1", chime()))
    for p in made:
        print(os.path.relpath(p))


if __name__ == "__main__":
    main()
