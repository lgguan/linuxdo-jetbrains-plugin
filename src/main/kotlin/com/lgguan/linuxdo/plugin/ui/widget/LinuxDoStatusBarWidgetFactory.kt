package com.lgguan.linuxdo.plugin.ui.widget

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.service.LinuxDoNotificationService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel

class LinuxDoStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "com.lgguan.linuxdo.plugin.ui.widget.LinuxDoStatusBarWidget"

    override fun getDisplayName(): String = "Linux Do (API Docs) Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return LinuxDoStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class LinuxDoStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    private val listenerLifetime = com.intellij.openapi.util.Disposer.newDisposable()
    @Volatile private var disposed = false
    private var notifications: LinuxDoNotificationService? = null

    private val label = JLabel("[Docs: 0]")

    init {
        label.border = JBUI.Borders.empty(0, 4)
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        label.toolTipText = "Linux Do (API Docs): 0 unread notifications"

        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    val count = notifications?.unreadCount ?: 0
                    if (count > 0) {
                        com.lgguan.linuxdo.plugin.ui.dialog.NotificationListPanel.showAsPopup(project, label)
                        return
                    }
                }
                val toolWindowManager = ToolWindowManager.getInstance(project)
                val toolWindow = toolWindowManager.getToolWindow(Constants.TOOL_WINDOW_ID)
                if (toolWindow != null) {
                    if (toolWindow.isVisible) {
                        toolWindow.hide()
                    } else {
                        toolWindow.show()
                    }
                }
            }
        })

        // Dynamic plugin installation constructs widgets on the EDT. Service creation
        // and credential restoration must not delay the widget or the rest of IDEA.
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed || project.isDisposed) return@executeOnPooledThread
            val service = LinuxDoNotificationService.getInstance()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed) return@invokeLater
                notifications = service
                service.addCountListener(listenerLifetime) { count ->
                    if (!disposed && !project.isDisposed) updateWidget(count)
                }
            }
        }
    }

    private fun updateWidget(unreadCount: Int) {
        if (unreadCount > 0) {
            label.text = "[Docs: $unreadCount]"
            label.foreground = JBColor.RED
            label.toolTipText = "Linux Do (API Docs): $unreadCount unread issues/mentions"
        } else {
            label.text = "[Docs]"
            label.foreground = JBColor.GRAY
            label.toolTipText = "Linux Do (API Docs): All caught up"
        }
    }

    override fun ID(): String = "com.lgguan.linuxdo.plugin.ui.widget.LinuxDoStatusBarWidget"

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {}

    override fun dispose() {
        disposed = true
        notifications = null
        com.intellij.openapi.util.Disposer.dispose(listenerLifetime)
    }
}
