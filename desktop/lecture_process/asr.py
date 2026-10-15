"""Speech recognition with faster-whisper on the GPU, plus cleanup of Whisper's typical
inventions on silence and noise.

Measured on the RTX 5070 Laptop (2026-10-15): large-v3 float16, ~25-45x realtime on short
test files. av < 17 is required: faster-whisper 1.2.1 passes an argument av 19 removed.
"""
import glob
import os
import re
import site
from dataclasses import dataclass, field

MODEL = "large-v3"

# Silero VAD is off: on the test recordings it dropped real, quiet speech at every threshold
# (0.35 and 0.1), while without it [clean] removes what Whisper invents on silence. Losing
# the lecturer's words is worse than a little noise the notes step can ignore.
USE_VAD = False
VAD = dict(threshold=0.35, min_silence_duration_ms=1000, speech_pad_ms=400)

# A piece the model itself doubts (likely noise or a far voice): kept, but marked "(?)".
UNSURE_NO_SPEECH = 0.6
UNSURE_LOGPROB = -1.0

# Whole segments Whisper produces on silence (learned from subtitle data), Russian and English.
JUNK = re.compile(
    r"субтитр|продолжение следует|спасибо за просмотр|подпис(ыв)?айтесь|ставьте лайк"
    r"|dimatorzok|amara\.org|thanks for watching",
    re.IGNORECASE,
)


@dataclass
class Word:
    start: float           # seconds from the beginning of the audio file
    end: float
    text: str
    prob: float


@dataclass
class Piece:
    start: float
    end: float
    text: str
    no_speech: float
    logprob: float
    words: list = field(default_factory=list)


def _cuda_dlls():
    """cuBLAS/cuDNN come as pip packages (nvidia-*-cu12); Windows must be told where they are."""
    for sp in site.getsitepackages():
        for d in glob.glob(os.path.join(sp, "Lib", "site-packages", "nvidia", "*", "bin")) + \
                glob.glob(os.path.join(sp, "nvidia", "*", "bin")):
            os.add_dll_directory(d)
            os.environ["PATH"] = d + os.pathsep + os.environ.get("PATH", "")


class Recognizer:
    """Loads the model once (a few seconds from the local cache, ~3 GB download the first time)."""

    def __init__(self, model=MODEL, device="auto"):
        _cuda_dlls()
        import ctranslate2
        from faster_whisper import WhisperModel
        if device == "auto":
            device = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
        self.device = device
        self.model = WhisperModel(model, device=device, compute_type="float16" if device == "cuda" else "int8")

    def transcribe(self, path, prompt=None, hotwords=None):
        """Raw pieces of one audio file; [clean] them before use (kept apart so the cache
        holds what the model heard and the filters can change without recognizing again)."""
        segments, _ = self.model.transcribe(
            str(path),
            language="ru",
            beam_size=5,
            word_timestamps=True,
            vad_filter=USE_VAD,
            vad_parameters=VAD if USE_VAD else None,
            # Without it one misheard window can make every following one repeat it.
            condition_on_previous_text=False,
            initial_prompt=prompt,
            # The initial prompt only reaches the first 30 s window when previous text is not
            # carried over; hotwords go into every window's prompt. On a real lecture (2026-10-15,
            # Материаловедение) this turned "аустинисты и христианцы" into "аустенит и цементит"
            # and halved the doubtful pieces, at ~2.5x the recognition time.
            hotwords=hotwords,
        )
        return [
            Piece(s.start, s.end, s.text.strip(), s.no_speech_prob, s.avg_logprob,
                  [Word(w.start, w.end, w.word, w.probability) for w in (s.words or [])])
            for s in segments
        ]


# Words of the prompt template itself (transcribe.prompt_for), never of the lecture's glossary.
PROMPT_FRAME = {"лекция", "предмету", "термины"}


def _letters(text):
    return re.sub(r"[^\w]+", " ", text.lower()).strip()


def unsure(piece) -> bool:
    return piece.no_speech > UNSURE_NO_SPEECH or piece.logprob < UNSURE_LOGPROB


def from_dict(d):
    return Piece(d["start"], d["end"], d["text"], d["no_speech"], d["logprob"], [Word(**w) for w in d["words"]])


def clean(pieces, prompt=None):
    """Drops subtitle credits, echoes of the prompt and near-certain non-speech; collapses
    repetition loops."""
    echo = set(_letters(prompt or "").split())
    out = []
    for p in pieces:
        if not p.text or JUNK.search(p.text):
            continue
        # On silence Whisper may "hear" its own prompt (subject name, glossary terms), in any order.
        # A real "изгибающий момент" is all glossary words too, so a piece made only of prompt
        # words is dropped only when it has the prompt's frame words or the model doubts it.
        said = _letters(p.text).split()
        if echo and len(said) >= 2 and all(w in echo for w in said) and (PROMPT_FRAME & set(said) or unsure(p)):
            continue
        if p.no_speech > 0.6 and p.logprob < -0.8:
            continue
        p.words = collapse_repeats(p.words)
        p.text = "".join(w.text for w in p.words).strip() if p.words else p.text
        if p.text:
            out.append(p)
    return out


def collapse_repeats(words, min_repeats=3, max_n=8):
    """"в том числе и в том числе и в том числе и ..." → once. Repeats twice are kept: people do that."""
    def norm(w):
        return re.sub(r"[^\w]", "", w.text.lower())

    out = list(words)
    i = 0
    while i < len(out):
        collapsed = False
        for n in range(1, max_n + 1):
            gram = [norm(w) for w in out[i:i + n]]
            if len(gram) < n or not any(gram):
                break
            k = 1
            while [norm(w) for w in out[i + k * n:i + (k + 1) * n]] == gram:
                k += 1
            if k >= min_repeats:
                del out[i + n:i + k * n]
                collapsed = True
                break
        if not collapsed:
            i += 1
    return out
