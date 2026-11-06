package com.opencodebuddy.files;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reflection bridge to the OpenCode Buddy host plugin.
 *
 * <p>Deliberately accesses the host through reflection and <em>optional</em>
 * {@code <depends>} so this overlay keeps working (and degrades gracefully) even
 * if the host renames a method or is upgraded. We never compile against the
 * host's classes and never load its symbols directly.</p>
 */
public final class HostBridge {

    private static final Logger LOG = Logger.getInstance(HostBridge.class);

    /** Stable plugin id of the host. */
    private static final String HOST_ID = "com.senjuobito.opencode-buddy";
    /** Host tool window id (constant {@code "OpenCode"} on {@code OpencodeBuddyToolWindow}). */
    public static final String TOOL_WINDOW_ID = "OpenCode";

    private static final String HOST_TOOL_WINDOW = "com.opencodebuddy.ui.toolwindow.OpencodeBuddyToolWindow";
    private static final String HOST_CHAT_WINDOW = "com.opencodebuddy.ui.toolwindow.OpencodeBuddyChatWindow";

    private HostBridge() {
    }

    /** True when the host plugin's classes are loadable (no initialization side effects). */
    public static boolean isHostPresent() {
        return findClass(HOST_TOOL_WINDOW) != null;
    }

    /**
     * True when the host is reachable for a given project. Falls back to the OpenCode
     * tool window being registered, so the action is enabled even if the exact host
     * class name changed slightly across host versions.
     */
    public static boolean isHostPresent(@NotNull Project project) {
        if (isHostPresent()) {
            return true;
        }
        try {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            return toolWindow != null;
        } catch (Throwable t) {
            LOG.debug("[FilesPlugin] tool window lookup failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Insert the given absolute file paths into the chat as `@file` references,
     * targeting the chat window that is currently visible in the OpenCode tool window.
     */
    public static void addFileReferences(@NotNull Project project, @NotNull List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        Object window = resolveChatWindow(project);
        if (window == null) {
            // Nothing to receive the files yet; make sure the chat is visible so the user
            // can re-invoke or so a subsequent drop lands. Buffering happens inside the
            // host's own PendingFileReferencesBuffer once the webview is ready.
            activateToolWindow(project);
            return;
        }
        invoke(window, "addFileReferencesFromExternal", new Class<?>[]{List.class}, new Object[]{paths});
        activateToolWindow(project);
    }

    /** Static, throttled diagnostic counters (avoid spamming the log on the 2s poll). */
    private static int lastAllWindowCount = -1;
    private static boolean loggedBrowserNull = false;
    private static boolean loggedBrowserComponent = false;

    /** All non-disposed chat windows of the project (used to register DnD targets). */
    @NotNull
    public static Collection<Object> getAllChatWindowsForProject(@NotNull Project project) {
        List<Object> out = new ArrayList<>();
        try {
            Class<?> hostClass = requiredHostClass();
            if (hostClass == null) {
                LOG.info("[FilesPlugin] host class not loadable (reflection bridge disabled)");
                return out;
            }
            Object result = invokeStatic(hostClass, "getAllChatWindowsForProject",
                    new Class<?>[]{Project.class}, new Object[]{project});
            if (result instanceof Collection<?> collection) {
                for (Object window : collection) {
                    if (window != null && !isTrue(invoke(window, "isDisposed", new Class<?>[0], new Object[0]))) {
                        out.add(window);
                    }
                }
            }
            int count = out.size();
            if (count != lastAllWindowCount) {
                lastAllWindowCount = count;
                LOG.info("[FilesPlugin] getAllChatWindowsForProject -> " + count + " window(s)");
            }
        } catch (Throwable t) {
            if (lastAllWindowCount != -1) {
                lastAllWindowCount = -2;
                LOG.info("[FilesPlugin] getAllChatWindowsForProject failed: " + t.getMessage());
            }
        }
        return out;
    }

    /**
     * Get the Swing component of a chat window's browser for DnD-target registration.
     *
     * <p>The host keeps the browser in a {@code private volatile JBCefBrowser browser} field
     * and only exposes {@code getBrowser()} through anonymous delegate objects, so we read the
     * field directly via reflection.</p>
     */
    @Nullable
    public static Object getBrowserComponent(@NotNull Object chatWindow) {
        Object browser = invoke(chatWindow, "getBrowser", new Class<?>[0], new Object[0]);
        if (browser == null) {
            try {
                java.lang.reflect.Field f = chatWindow.getClass().getDeclaredField("browser");
                f.setAccessible(true);
                browser = f.get(chatWindow);
            } catch (Throwable t) {
                if (!loggedBrowserNull) {
                    loggedBrowserNull = true;
                    LOG.info("[FilesPlugin] browser field not readable: " + t.getMessage());
                }
                return null;
            }
        }
        if (browser == null) {
            if (!loggedBrowserNull) {
                loggedBrowserNull = true;
                LOG.info("[FilesPlugin] browser is null");
            }
            return null;
        }
        Object comp = invoke(browser, "getComponent", new Class<?>[0], new Object[0]);
        if (comp != null && !loggedBrowserComponent) {
            loggedBrowserComponent = true;
            LOG.info("[FilesPlugin] browser component: " + comp.getClass().getName());
        }
        return comp;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    @Nullable
    private static Object resolveChatWindow(@NotNull Project project) {
        // Prefer the content tab that is actually selected in the tool window,
        // so a drop/action inserts into the visible conversation (not tab #1).
        try {
            Class<?> hostClass = requiredHostClass();
            if (hostClass == null) {
                return null;
            }
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            if (toolWindow != null) {
                Content selected = toolWindow.getContentManager().getSelectedContent();
                if (selected != null) {
                    Object window = invokeStatic(hostClass, "getChatWindowForContent",
                            new Class<?>[]{Content.class}, new Object[]{selected});
                    if (window != null) {
                        return window;
                    }
                }
            }
            return invokeStatic(hostClass, "getChatWindow",
                    new Class<?>[]{Project.class}, new Object[]{project});
        } catch (Throwable t) {
            LOG.debug("[FilesPlugin] resolveChatWindow failed: " + t.getMessage());
            return null;
        }
    }

    private static void activateToolWindow(@NotNull Project project) {
        try {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            if (toolWindow != null) {
                toolWindow.show();
            }
        } catch (Throwable t) {
            LOG.debug("[FilesPlugin] activateToolWindow failed: " + t.getMessage());
        }
    }

    @Nullable
    private static Class<?> requiredHostClass() {
        Class<?> hostClass = findClass(HOST_TOOL_WINDOW);
        if (hostClass == null) {
            LOG.debug("[FilesPlugin] OpenCode Buddy host not present (" + HOST_ID + ")");
        }
        return hostClass;
    }

    @Nullable
    private static Class<?> findClass(String name) {
        try {
            // initialize=false: probing must not tick the host's static initializers;
            // we only need to know the type is loadable through our plugin classloader.
            return Class.forName(name, false, HostBridge.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Object invokeStatic(@NotNull Class<?> owner, String method, Class<?>[] types, Object[] args) {
        try {
            Method m = owner.getMethod(method, types);
            return m.invoke(null, args);
        } catch (Throwable t) {
            LOG.debug("[FilesPlugin] invokeStatic " + method + " failed: " + t.getMessage());
            return null;
        }
    }

    @Nullable
    private static Object invoke(Object target, String method, Class<?>[] types, Object[] args) {
        try {
            Method m = target.getClass().getMethod(method, types);
            return m.invoke(target, args);
        } catch (Throwable t) {
            LOG.debug("[FilesPlugin] invoke " + method + " failed: " + t.getMessage());
            return null;
        }
    }

    private static boolean isTrue(@Nullable Object value) {
        return value instanceof Boolean b && b;
    }
}
