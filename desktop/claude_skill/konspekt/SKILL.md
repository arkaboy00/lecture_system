---
name: konspekt
description: Write Obsidian lecture notes (конспект) from lectures recorded with Lecture Capture and processed on this laptop. Use when the user asks for a конспект / notes of a lecture or seminar, "конспект по последней лекции", "по всем новым", or runs /konspekt.
---

# Конспект лекции из записи

Lectures are recorded on the phone (audio + photos of the board and notebook), synced to
this laptop and processed by `lecture_desktop` («Обработать»): speech is recognized and
every lecture gets a `notes_input/` folder. You turn that folder into a note in the user's
Obsidian vault. Write the note in Russian.

## 1. Find the lectures

```
C:\Users\User\Documents\_projects\Claude\lecture_desktop\.venv\Scripts\python -m lecture_process status --json
```

Run it from `C:\Users\User\Documents\_projects\Claude\lecture_desktop`. It prints the vault
path and every processed lecture with `state`: `new` (no note yet), `changed` (the note was
written from an older input: more audio, other photo marks, renamed) or `done`.

- "последняя лекция" = the newest by date that is not `done`, or the newest overall if all
  are done (then ask before rewriting it).
- "все новые" = every `new` and `changed`, one after another.
- A named subject/date: match on `subject`, `title`, `date`.
- Nothing processed or the lecture missing: tell the user to press «Синхронизировать» and
  «Обработать» in the «Лекции» window (desktop icon), and stop.

## 2. Photos first, then a second recognition pass

The lecture's own terms make recognition much better (2026-10-15, Материаловедение: "сера
вызывает красный огонь" became "сера вызывает красноломкость", doubtful pieces 14 → 2 per
26 minutes). You read the photos for the note anyway, so collect the terms on the way.

1. `notes_input/info.json`: subject, title, `kind` (`lecture` | `seminar`), `part` (2, 3…
   when the lecture was recorded in several parts, e.g. two pairs with a break), date,
   photos (each with `type` and `redraw`), `notes` and `marks` counts, `input_version`,
   `slides` (matched sections of the course materials, if any).
2. **Every photo** in `notes_input/photos/`, with the Read tool, in order, **once** (do not
   re-read them later; take notes as you go). Board photos are the source of truth for
   formulas, notation, diagrams and the structure; notebook photos are the user's own
   notes, usually the clearest summary; `slide` photos are a projected presentation (an
   older "board" photo may be one too). Read what is legible, do not guess the rest. Note
   anything like «будет в РК / на экзамене». A handwritten symbol that is ambiguous on a
   photo (Ц_II vs Ц_III) is written as the subject's logic and the slides say, not as a
   literal misreading.
3. Write `<work>/<folder>/terms.txt` (`<work>` is in the status output; one term per line):
   subject terms you read with confidence on the photos, **in the order they appear,
   notebook photos first, then board photos** (do not re-sort: measured, sorting made it
   worse): rare nouns, structures, phases, processes, surnames, grades like «У8». Skip common
   words Whisper knows anyway («сталь», «температура»). 20–40 lines; only the first ~15
   (~300 characters) reach the recognizer, followed by the subject glossary, so the
   notebook's terms count most. If the file exists, keep its lines and
   add new ones at the end.
4. Run the second pass from the lecture_desktop folder (in the background; about 10 minutes
   per hour of audio, instant when nothing changed) and wait for it:
   `.venv\Scripts\python -m lecture_process process <folder>`
   It rebuilds `notes_input/` (`input_version` changes: use the new one).

## 3. Read the text

1. `notes_input/transcript.md`: the whole of it. It is automatic speech recognition:
   - terms, names and especially formulas are often misheard ("а пюре" = "эпюра");
   - `(?)` before a phrase: the model doubts it is speech (noise, a far voice, music); it may
     be invented. Use it only when it fits the topic or a photo, never as the sole source;
   - `[ФОТО n · time · доска|тетрадь|слайд · photos/…]` marks the moment a photo was taken:
     the speech around it explains that photo; `· перерисовать` = the user wants its drawings
     redrawn properly (section 4);
   - `[ЗАМЕТКА · time] text`: the user's own remark typed on the phone (the time is when
     they started typing). Take it seriously: it is a correction, a reminder or a question;
   - `[ОТМЕЧЕНО КНОПКОЙ · time]`: the user pressed "important" here. The important thing was
     said **just before** (up to a minute or two): find it in the speech (section 5);
   - `— запись остановлена … —` / `— запись оборвалась … —` are gaps: nothing was recorded.
2. `notes_input/slides.md`, if present: text of the course-material sections that look like
   this lecture (picked automatically, some may be unrelated: skip those). Use it for exact
   wording of definitions, classifications and numbers, and link pages from the note as
   `[[<pdf>#page=N|слайд N]]` instead of embedding photos of the same slides. It never
   replaces what was said: the lecture's own content and order come first.
3. The subject glossary, if present:
   `C:\Users\User\Documents\_projects\Claude\lecture_desktop\glossary\<subject>.txt`.

## 4. Write the note

Path: `<vault>/<subject>/<date> <title>.md` (e.g. `Сопромат/2026-10-15 Лекция 1.md`). Never
overwrite an existing file: if it exists (state `changed`, or a name clash), write
`<date> <title> (обновлено <today>).md` and say so; the user may have edited the old one.

Copy the photos you embed to `<vault>/<subject>/attachments/<date> <title>/` (keep their
names) and embed them as `![[attachments/<date> <title>/<file>|600]]`.

### Front matter (exactly these keys: `источник` and `версия_входа` tell the status command
which lecture and input the note was written from; `tags` is Obsidian's own key)

```
---
предмет: <subject>
тип: лекция | семинар
дата: <date>
название: <title>
тема: <main topic in a few words>
источник: <info.folder>
версия_входа: <info.input_version>
tags: [конспект, <subject in lowercase, spaces → дефисы>]
---
```

### Lecture (`kind: lecture`)

```
# <title>: <тема>

> [!summary] Кратко
> 2–4 предложения: о чём лекция и что из неё главное.

## <Раздел 1>
...
## Неясные места
## Полный текст
```

- **Sections follow the lecture's own structure** (what the lecturer wrote as headings on
  the board, or clear topic changes in speech). Not one section per photo.
- Inside: definitions, statements, formulas, derivations in their logical order, short
  explanations of *why* in the lecturer's words. Compact, but nothing important dropped.
- Formulas in LaTeX: `$...$` inline, `$$...$$` display. Take notation from the board
  (letters, indices, signs) and keep it consistent through the note.
- Callouts: `> [!definition] Определение` — `> [!theorem] Теорема` (or `[!important]`) —
  `> [!example] Пример`. Obsidian renders unknown callout types as notes, which is fine.
- Embed a photo where a diagram, a drawing or a big derivation is easier to see than to
  retype; formulas themselves are always typed in LaTeX too.
- **Photos and slides are folded**, so the text reads clean and one click opens the
  picture: `> [!note]- Фото 3 — <what it shows>` (or `Слайд N — <title>`) followed by
  `> ![[attachments/<date> <title>/<file>|600]]`.
- **A projected slide that exists in the course PDF** (`<vault>/<subject>/Материалы/*.pdf`;
  slides.md lists the matched pages, search the PDF text for others) is shown as a render of
  that PDF page instead of the photo, and every `[[<pdf>#page=N|слайд N]]` link gets that
  render too. Render with PyMuPDF from the lecture_desktop venv:
  `.venv\Scripts\python -c "import pymupdf,sys; d=pymupdf.open(sys.argv[1]); d[int(sys.argv[2])-1].get_pixmap(dpi=80).save(sys.argv[3])" <pdf> <page> <vault>/<subject>/attachments/<date> <title>/slides/slide_<page>.png`
- **Photos flagged `перерисовать`**: redraw the drawing in the note as cleanly as you can:
  a Mermaid diagram for schemes and block diagrams, a Markdown table for tables, LaTeX for
  formulas, an SVG file in the attachments folder for plots and diagrams with curves
  (`![[…/fig_<n>.svg]]`). Keep the original photo folded right under it, and say in the
  callout title what is approximate (phase diagrams and curves rarely come out exact).
- **The user's notes** (`[ЗАМЕТКА]`) go where they belong in the text as
  `> [!note] Моя заметка` with their wording; a question in a note that the lecture
  answers is answered right there.
- What the lecturer stressed ("это будет на экзамене", "запомните") goes into a
  `> [!important]` callout.
- **Parts** (`part` ≥ 2 in info.json, title «… ч.2»): one note per part. Add
  `часть: <N>` to the front matter and, under the summary, a link to the previous part
  (`Начало: [[<date> <title of part N-1>]]`; find it with `status`). If the previous part's
  note exists, add a `Продолжение: [[…]]` line under its summary (the only edit you may make
  to an existing note).

### Seminar (`kind: seminar`)

Same front matter and summary, then one section per problem:

```
## Задача 1. <short name>
**Условие.** ...
**Решение.** steps with formulas
**Ответ.** ...
```

Then `## Неясные места` and `## Полный текст` as for a lecture.

### Неясные места

A list of what you could not resolve: a formula that is neither legible on a photo nor
clear from speech, a misheard term, a gap in the recording that cuts a derivation. Each item
with the time from the transcript (`[01:24:25]`) so the user can find it in the audio or
photo. Never fill such places with invented content; mark them `(?)` in the text and list
them here. If there is nothing, write "нет".

### Полный текст

The recognized speech cleaned up for reading: misheard terms fixed (from photos, glossary
and the subject), filler and false starts removed, split into paragraphs with the clock
times kept at paragraph starts, photo moments shown as `📷 n`. Do not summarize here: this
is the fallback when the summary above is not enough. Put it in a folded callout so the
note stays short:

```
> [!quote]- Полный текст (распознано автоматически)
> [01:21:46] ...
>
> [01:22:26] ...
```

## 5. Контроль (что повторить к РК и экзамену)

Whenever the lecturer says something will be on a test («будет на РК / рубежном контроле /
контрольной / экзамене», «это спросят», «запомните к экзамену»), the user wrote such a
note on a photo («Всё выше будет в РК М1») or in a `[ЗАМЕТКА]`, or pressed the button
(`[ОТМЕЧЕНО КНОПКОЙ]`: look at the minute or two of speech before it), add entries to
`<vault>/<subject>/Контроль/<file>.md`: `РК-1.md`, `РК-2.md`, …, `Экзамен.md`; when it is
unclear which one, `Разобрать.md`. Create a missing file with a `# <name>` heading. Per entry:

```
- [ ] <тема коротко>: <что именно повторить> — [[<subject>/<note name>#<heading>|<date>]] `id:<folder>@<HH:MM:SS>`
```

The `id` makes this idempotent: on a rewrite, update the line with the same id instead of
adding a duplicate, and keep the user's `[x]`. For a button mark the time in the id is the
mark's own time; entries you found in the speech without a mark end with ` *(авто)*` so the
user can check them quickly. A mark whose speech says nothing about a test is still
important: put it into `Разобрать.md` with what was said. «Всё выше» means everything of this lecture
before that moment (and earlier lectures if the lecturer says so): list the topics. Also put
an `> [!important]` callout in the note itself. No mentions: create nothing.

## 6. Rules

- Content comes from this lecture only (speech + photos). Use your knowledge of the subject
  to fix recognition errors and notation, not to add material the lecturer did not give;
  a missing step you supply yourself is marked `*(дополнено)*`.
- Off-topic talk (organizational remarks, jokes, noise) is left out of the summary part.
- If the recording is not a lecture at all (a test, silence), write a short note saying so
  instead of inventing a lecture, and tell the user.
- After writing, rebuild the vault's contents pages (`<subject>/<subject>.md`, `Главная.md`;
  generated, never edit them by hand), from the lecture_desktop folder:
  `.venv\Scripts\python -m lecture_process index`
- Spend tokens where they matter: photos and the transcript are read once; slides.md
  instead of re-reading slide photos; no copying of whole slides into the note.
- Then reply with the note path, the number of photos used, the «Неясные места»
  count, Контроль entries added, and (separately) subject terms you met that are missing
  from the glossary, as a suggestion; do not edit the glossary yourself.
