package dev.gostex.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/** A real separate process for validating launch/exit; touches no game or user files. */
object ReturnTest {
    @JvmStatic fun main(args: Array<String>) {
        SwingUtilities.invokeLater {
            val frame = JFrame(args.getOrElse(0) { "GOSTEX" })
            frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            frame.contentPane.background = Color(16, 18, 22)
            val label = JLabel(args.getOrElse(1) { "" }, SwingConstants.CENTER).apply {
                foreground = Color.WHITE
                font = Font(Font.SANS_SERIF, Font.PLAIN, 20)
                border = BorderFactory.createEmptyBorder(45, 30, 45, 30)
            }
            val close = JButton(args.getOrElse(2) { "GOSTEX" }).apply {
                addActionListener { frame.dispose(); System.exit(0) }
                addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(event: KeyEvent) { if (event.keyCode == KeyEvent.VK_ESCAPE) { frame.dispose(); System.exit(0) } }
                })
            }
            frame.add(label, BorderLayout.CENTER)
            frame.add(close, BorderLayout.SOUTH)
            frame.rootPane.defaultButton = close
            frame.setSize(850, 320)
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
            close.requestFocusInWindow()
        }
    }
}
