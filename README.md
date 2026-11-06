# OpenCode Buddy - Files to Context (overlay plugin)

A thin **overlay plugin** (`com.opencodebuddy.files`) for JetBrains Rider that adds two
portable ways to push project files into the **OpenCode Buddy** chat:

1. **Add to OpenCode Context** - right-click a file / multi-selection in the Solution
   Explorer (or Project view / editor) and choose *Add to OpenCode Context*. It inserts
   `@<path>` into the active chat input.
2. **Drag & drop** - drag a file (or files) from the Solution tree onto the OpenCode chat
   window and release, to insert them into the prompt.

## Design

This plugin is deliberately a **separate overlay** and does **not** modify the official
`com.senjuobito.opencode-buddy` host plugin:

- It declares `<depends>com.senjuobito.opencode-buddy</depends>` so it loads **after** the
  host and its classes are visible to the overlay's classloader, and can be updated
  independently (the host keeps updating normally).
- It only talks to the host through **reflection** (`Class.forName` + `Method.invoke`), never
  compiling against the host's classes. If the host renames a public method, the overlay
  degrades gracefully instead of breaking.
- It registers in standard, always-available action groups declaratively, and injects the
  action into Rider's `SolutionExplorerPopupMenu` **at runtime** (that group is created by
  Rider's core after plugin load, so it cannot be referenced in `plugin.xml`).

### Reflection bridge targets

Reached via reflection on `com.opencodebuddy.ui.toolwindow.OpencodeBuddyToolWindow` /
`OpencodeBuddyChatWindow`:

| Host method | Purpose |
|---|---|
| `getChatWindow(Project)` / `getChatWindowForContent(Content)` | resolve the active chat window |
| `getAllChatWindowsForProject(Project)` | enumerate chat windows (for DnD registration) |
| `addFileReferencesFromExternal(List<String>)` | insert `@<path>` references into the chat |
| `getBrowser()` (private field `browser` read directly) | obtain the Swing browser component for the DnD target |

Note: `OpencodeBuddyChatWindow` keeps its `JBCefBrowser` in a `private volatile browser`
field and only exposes `getBrowser()` via anonymous delegate objects, so the overlay reads
the field with `getDeclaredField("browser")` + `setAccessible(true)`.

### DnD notes

The drop target uses IntelliJ's stable `com.intellij.ide.dnd.DnDTarget` /
`DnDManager.registerTarget(...)` API and reads only the **IDE-internal** payload
(`com.intellij.ide.dnd.TransferableWrapper` -> `VirtualFile[]`). It deliberately ignores
`javaFileListFlavor`, because plain OS-file drops are already handled by the host's own AWT
`DropTarget` - consuming those too would double-insert files.

The chat renders via JCEF (an OSR `JBCefOsrComponent`). Under certain IDE/JCEF states the
OSR component may intercept pointer events and prevent the drop from dispatching; when that
happens, **the context menu is the reliable path**. The overlay includes diagnostics
(`[FilesPlugin]` log lines) to tell which case you are in.

## Layout

```
src/main/java/com/opencodebuddy/files/
  HostBridge.java                     reflection bridge to the host
  action/AddToOpenCodeContextAction.java   the popup action (file/PSI detection)
  dnd/ChatDropTarget.java             IDE-internal DnDTarget for the chat browser
  dnd/DndRegistrar.java               startup activity: DnD registration + action injection
src/main/resources/META-INF/plugin.xml
build.ps1                             offline compile + package (Rider JBR javac)
```

## Build (fully offline)

No Gradle, no network. Uses the **JBR that ships with Rider** and the installed Rider's
`lib\*.jar` as the compile classpath.

```powershell
# From this repo
.\build.ps1 -RiderHome "C:\Program Files\JetBrains\JetBrains Rider 2026.2.3.1"
# Produces: build\opencode-buddy-files.jar
```

You can also pass `-Install` to copy the jar into the Rider plugins dir
(`...\Rider2026.2\plugins\opencode-buddy-files\lib\opencode-buddy-files.jar`). The jar is
written with **forward-slash** entry names (required for IntelliJ to find
`META-INF/plugin.xml` and class resources).

### Manual install

1. Copy/build `opencode-buddy-files.jar`.
2. Place it at `%APPDATA%\JetBrains\Rider2026.2\plugins\opencode-buddy-files\lib\opencode-buddy-files.jar`.
3. Fully restart Rider.

## Diagnostics

`[FilesPlugin]` INFO lines in `%LOCALAPPDATA%\JetBrains\Rider2026.2\log\idea.log`:

- `AddToOpenCodeContextAction constructed` - action registered
- `action added to SolutionExplorerPopupMenu` - injected into the Solution Explorer menu
- `action update -> host/files` - action enabled with a selection
- `getAllChatWindowsForProject -> N window(s)` - found the chat window
- `browser component: com.intellij.ui.jcef.JBCefOsrComponent` - got the Swing component
- `DnD target registered on ...` - drop target attached
- `Add to OpenCode Context: N file(s)` / `DnD: dropping N file(s)` - files routed to chat

## Environments verified

- JetBrains Rider 2026.2 (build `RD-262.11879.119`), JBR 25, `--release 17`.
- Host plugin `com.senjuobito.opencode-buddy` 2.0.5.
