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

## 2. Read everything

From `notes_input/`:
1. `info.json`: subject, title, `kind` (`lecture` | `seminar`), date, photos, `input_version`.
2. `transcript.md`: the whole of it. It is automatic speech recognition (Whisper):
   - terms, names and especially formulas are often misheard ("а пюре" = "эпюра");
   - `(?)` before a phrase: the model doubts it is speech (noise, a far voice, music); it may
     be invented. Use it only when it fits the topic or a photo, never as the sole source;
   - `[ФОТО n · time · доска|тетрадь · photos/…]` marks the moment a photo was taken: the
     speech around it explains that photo;
   - `— запись остановлена … —` / `— запись оборвалась … —` are gaps: nothing was recorded.
3. **Every photo** in `photos/`, with the Read tool, in order. Board photos are the source
   of truth for formulas, notation, diagrams and the structure of the topic; notebook photos
   are the user's own notes. A photo may show a screen (online lecture) or a part of the
   board; read what is legible, do not guess the rest.
4. The subject glossary, if present:
   `C:\Users\User\Documents\_projects\Claude\lecture_desktop\glossary\<subject>.txt`.

## 3. Write the note

Path: `<vault>/<subject>/<date> <title>.md` (e.g. `Сопромат/2026-10-15 Лекция 1.md`). Never
overwrite an existing file: if it exists (state `changed`, or a name clash), write
`<date> <title> (обновлено <today>).md` and say so; the user may have edited the old one.

Copy the photos you embed to `<vault>/<subject>/attachments/<date> <title>/` (keep their
names) and embed them as `![[attachments/<date> <title>/<file>|600]]`.

### Front matter (exactly these keys: the status command reads `источник` and `версия_входа`)

```
---
предмет: <subject>
тип: лекция | семинар
дата: <date>
название: <title>
тема: <main topic in a few words>
источник: <info.folder>
версия_входа: <info.input_version>
теги: [конспект, <subject in lowercase, spaces → дефисы>]
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
- What the lecturer stressed ("это будет на экзамене", "запомните") goes into a
  `> [!important]` callout.

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

## 4. Rules

- Content comes from this lecture only (speech + photos). Use your knowledge of the subject
  to fix recognition errors and notation, not to add material the lecturer did not give;
  a missing step you supply yourself is marked `*(дополнено)*`.
- Off-topic talk (organizational remarks, jokes, noise) is left out of the summary part.
- If the recording is not a lecture at all (a test, silence), write a short note saying so
  instead of inventing a lecture, and tell the user.
- After writing: reply with the note path, the number of photos used, the «Неясные места»
  count, and (separately) subject terms you met that are missing from the glossary, as a
  suggestion; do not edit the glossary yourself.
