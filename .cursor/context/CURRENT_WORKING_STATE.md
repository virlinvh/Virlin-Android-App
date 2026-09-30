# Current working tree — Cursor warning

**Not** a DEVELOPMENT_STATUS duplicate. Purpose: protect intentional uncommitted work.

## Do not

- `git reset` / `restore` / `checkout` / `clean` / `stash`
- stage or commit unless the user explicitly asks
- overwrite these diffs while adding Cursor-only files

## Uncommitted groups (as of Cursor integration pass)

| Group | Files |
|---|---|
| **A. GLM compatibility layer** | `GLM.md`, `.glm/**` (untracked) |
| **B. Orb ride-the-sheet / travel removed** | `app/.../ui/navigation/VirlinApp.kt` |
| **C. Orb hit-test fix** | `app/.../ui/components/VirlinOrb.kt` |
| **D. Control fixed-header / scroll regions** | `AgentControlArea.kt`, `AgentSheet.kt` |
| **E. Control golden** | `app/src/test/screenshots/agent_control_live.png` |
| **F. Status notes** | `docs/DEVELOPMENT_STATUS.md` |
| **G. Capture File / Image** | File viewer + Room v6 + attachments |
| **H. Capture Voice** | Voice editor + Room v7 + `voice_documents` / `filesDir/voices/` |

Approx. dirty tracked + untracked: **large** (Note/Prompt/Link/File/Voice + Control/Orb + Cursor/GLM layers). Re-check with `git status`.

## Behaviour already in that dirty tree (do not casually undo)

- Orb: no visible root→Agent travel; Orb rides the rising sheet; alpha-0 Orb must not steal hits
- Control: fixed Quick Actions + RECENT/SUGGESTED heading; only middle list/picker scrolls; composer pinned

## Environment

Windows Application Control may block `robolectric-nativeruntime.dll` → Roborazzi/Robolectric
screenshot verify fails. Classify as **environment**, not product. Do not bypass security.

Re-check with `git status` at the start of every task — this file can lag if new work lands.
