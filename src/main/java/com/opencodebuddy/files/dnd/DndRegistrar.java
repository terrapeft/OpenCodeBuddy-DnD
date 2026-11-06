package com.opencodebuddy.files.dnd;

import com.intellij.ide.dnd.DnDManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Registers {@link ChatDropTarget}s on the OpenCode Buddy chat browser components.
 *
 * <p>The host creates the browser lazily (and may recreate it, e.g. on tab switch),
 * so we keep a lightweight Swing timer polling for the project's whole lifetime.
 * Registration is idempotent per component and routed through the IDE's {@link DnDManager},
 * the stable API for IDE-internal (Project/Solution view) drops.</p>
 */
public final class DndRegistrar implements StartupActivity.Background {

    private static final Logger LOG = Logger.getInstance(DndRegistrar.class);
    private static final int POLL_INTERVAL_MS = 2000;

    /** Our action id, used to inject it into Rider's Solution Explorer popup at runtime. */
    private static final String ACTION_ID = "OpenCodeBuddyFiles.AddToContext";
    /** Rider's Solution Explorer popup group. Registered lazily by Rider's core, so it must be
     *  resolved at runtime (referencing it declaratively fails at plugin load). */
    private static final String SOLUTION_EXPLORER_GROUP = "SolutionExplorerPopupMenu";

    // Weak keys so a recreated browser component can be garbage-collected; the set is
    // only ever touched on the EDT (timer + action), so plain phi-synchronization suffices.
    private static final Set<JComponent> REGISTERED =
            Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void runActivity(@NotNull Project project) {
        if (project.isDisposed()) {
            return;
        }
        LOG.info("[FilesPlugin] DndRegistrar started (backgroundPostStartupActivity)");
        // StartupActivity.Background runs on a worker thread; Swing setup must be on the EDT.
        SwingUtilities.invokeLater(() -> schedulePolling(project));
    }

    private static void schedulePolling(@NotNull Project project) {
        if (project.isDisposed()) {
            return;
        }
        Timer timer = new Timer(POLL_INTERVAL_MS, e -> {
            if (project.isDisposed()) {
                ((Timer) e.getSource()).stop();
                return;
            }
            ensureRegistered(project);
        });
        timer.setRepeats(true);
        timer.setCoalesce(true);
        Disposable disposable = () -> timer.stop();
        Disposer.register(project, disposable);
        timer.start();
        ensureRegistered(project);
    }

    /**
     * One idempotent pass over the project's chat windows, registering a drop target
     * on each live browser component. Safe to call on the EDT (from the timer or the
     * context action), and safe to call repeatedly.
     *
     * @return true when at least one new component was registered this pass.
     */
    public static boolean ensureRegistered(@NotNull Project project) {
        if (project.isDisposed()) {
            return false;
        }
        boolean any = false;
        // Inject the context action into Rider's Solution Explorer popup (idempotent).
        injectActionIntoSolutionExplorer();
        // Register DnD targets on the chat browser components.
        Collection<Object> windows = com.opencodebuddy.files.HostBridge.getAllChatWindowsForProject(project);
        for (Object window : windows) {
            Object comp = com.opencodebuddy.files.HostBridge.getBrowserComponent(window);
            if (comp instanceof JComponent component) {
                if (registerOnComponent(project, component)) {
                    any = true;
                }
            }
        }
        return any;
    }

    /**
     * Adds our context action into Rider's {@code SolutionExplorerPopupMenu} once that group
     * has been registered by Rider's core. Safe to call repeatedly (idempotent).
     */
    private static void injectActionIntoSolutionExplorer() {
        try {
            ActionManager am = ActionManager.getInstance();
            AnAction action = am.getAction(ACTION_ID);
            if (action == null) {
                return;
            }
            AnAction target = am.getAction(SOLUTION_EXPLORER_GROUP);
            if (!(target instanceof DefaultActionGroup group)) {
                // Not registered yet (or a non-mutable group); poll again next tick.
                return;
            }
            boolean present = false;
            for (AnAction child : group.getChildren((AnActionEvent) null)) {
                if (ACTION_ID.equals(am.getId(child))) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                group.add(action);
                LOG.info("[FilesPlugin] action added to SolutionExplorerPopupMenu");
            }
        } catch (Throwable t) {
            LOG.info("[FilesPlugin] injectActionIntoSolutionExplorer failed: " + t.getMessage());
        }
    }

    private static boolean registerOnComponent(@NotNull Project project, @NotNull JComponent component) {
        if (REGISTERED.contains(component)) {
            return false;
        }
        try {
            DnDManager.getInstance().registerTarget(new ChatDropTarget(project), component);
            REGISTERED.add(component);
            LOG.info("[FilesPlugin] DnD target registered on " + component.getClass().getName());
            return true;
        } catch (Throwable t) {
            LOG.info("[FilesPlugin] registerTarget failed: " + t.getMessage());
            // A component may be mid-disposal; retry on the next tick.
            return false;
        }
    }
}
