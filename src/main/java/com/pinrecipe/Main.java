package com.pinrecipe;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;

public final class Main {
    public static void main(String[] args) {
        DpiUtil.enableDpiAwareness();

        if (args.length > 0 && "--self-test".equals(args[0])) {
            SelfTest.run();
            return;
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    new PinRecipeApp().start();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    System.exit(1);
                }
            }
        });
    }
}

final class SelfTest {
    private SelfTest() {
    }

    static void run() {
        try {
            InputStream stream = Main.class.getResourceAsStream("/Pin.png");
            if (stream == null) {
                throw new IllegalStateException("Pin.png resource is missing.");
            }
            BufferedImage image;
            try {
                image = ImageIO.read(stream);
            } finally {
                stream.close();
            }
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new IllegalStateException("Pin.png resource could not be decoded.");
            }

            int threadId = Kernel32.INSTANCE.GetCurrentThreadId();
            Rectangle bounds = DisplayUtil.getVirtualBounds();
            final boolean[] hotkeyReceived = new boolean[]{false};
            HotkeyManager manager = new HotkeyManager();
            manager.start(Hotkey.f8(), new Runnable() {
                @Override
                public void run() {
                    hotkeyReceived[0] = true;
                }
            });
            Thread.sleep(300);
            if (!manager.isKeyboardWatcherRunning()) {
                manager.stop();
                throw new IllegalStateException("Keyboard watcher could not be started.");
            }
            Robot robot = new Robot();
            robot.keyPress(KeyEvent.VK_F8);
            Thread.sleep(120);
            robot.keyRelease(KeyEvent.VK_F8);
            for (int i = 0; i < 10 && !hotkeyReceived[0]; i++) {
                Thread.sleep(100);
            }
            manager.stop();
            System.out.println("PinRecipe self-test OK");
            System.out.println("Pin.png: " + image.getWidth() + "x" + image.getHeight());
            System.out.println("User32 thread id: " + threadId);
            System.out.println("Keyboard watcher: started");
            if (hotkeyReceived[0]) {
                System.out.println("Synthetic F8: received");
            } else {
                System.out.println("Synthetic F8: not observed in this desktop session");
            }
            System.out.println("Virtual bounds: " + bounds.x + "," + bounds.y + " " + bounds.width + "x" + bounds.height);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            System.exit(1);
        }
    }
}

final class PinRecipeApp {
    private static final String SHOW_LAUNCHER_ICON_PREFERENCE = "showLauncherIcon";

    private final List<PinWindow> pins = new ArrayList<PinWindow>();
    private final List<MiniPinWindow> minimizedPins = new ArrayList<MiniPinWindow>();
    private final HotkeyManager hotkeyManager = new HotkeyManager();
    private final IcarusDetector detector = new IcarusDetector();
    private final Rectangle virtualBounds = DisplayUtil.getVirtualBounds();
    private final Robot robot;
    private final BufferedImage pinImage;
    private final List<Image> appIcons;

    private LauncherWindow launcherWindow;
    private SettingsWindow settingsWindow;
    private TrayIcon trayIcon;
    private CheckboxMenuItem showLauncherIconTrayItem;
    private Timer gamePollTimer;
    private boolean gameRunning;
    private boolean launcherLocationInitialized;
    private boolean showLauncherIcon = loadShowLauncherIconPreference();
    private Hotkey hotkey = Hotkey.f8();

    PinRecipeApp() throws AWTException, IOException {
        robot = new Robot();
        pinImage = loadImage("/Pin.png");
        appIcons = createIconImages(pinImage);
    }

    void start() {
        setupTray();
        setTaskbarIcon();
        launcherWindow = new LauncherWindow(this, pinImage);
        settingsWindow = new SettingsWindow(this);
        hotkeyManager.start(hotkey, new Runnable() {
            @Override
            public void run() {
                beginCapture();
            }
        });

        gamePollTimer = new Timer(3000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshGameState();
            }
        });
        gamePollTimer.setInitialDelay(0);
        gamePollTimer.start();
    }

    BufferedImage getPinImage() {
        return pinImage;
    }

    List<Image> getAppIcons() {
        return appIcons;
    }

    Hotkey getHotkey() {
        return hotkey;
    }

    boolean isLauncherIconShown() {
        return showLauncherIcon;
    }

    void setLauncherIconShown(boolean shown) {
        showLauncherIcon = shown;
        saveShowLauncherIconPreference(shown);

        if (launcherWindow != null) {
            ensureLauncherLocationInitialized();
            launcherWindow.setVisible(gameRunning && showLauncherIcon);
        }
        if (settingsWindow != null) {
            settingsWindow.refreshLauncherIconSetting();
        }
        if (showLauncherIconTrayItem != null) {
            showLauncherIconTrayItem.setState(showLauncherIcon);
        }
        layoutMinimizedPins();
    }

    void setHotkey(Hotkey nextHotkey) {
        hotkey = nextHotkey;
        hotkeyManager.setHotkey(hotkey);
        if (settingsWindow != null) {
            settingsWindow.refreshHotkey();
        }
    }

    void startRecordingHotkey(final SettingsWindow.HotkeyRecordingTarget target) {
        hotkeyManager.startRecording(new HotkeyManager.Recorder() {
            @Override
            public void recorded(final Hotkey nextHotkey) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        setHotkey(nextHotkey);
                        target.recordingFinished(nextHotkey);
                    }
                });
            }
        });
    }

    void cancelRecordingHotkey() {
        hotkeyManager.cancelRecording();
    }

    void showSettings() {
        if (settingsWindow == null) {
            return;
        }
        ensureLauncherLocationInitialized();
        settingsWindow.refreshHotkey();
        settingsWindow.refreshLauncherIconSetting();
        Point base = launcherWindow != null ? launcherWindow.getLocationOnScreenSafe() : new Point(virtualBounds.x + 16, virtualBounds.y + 16);
        settingsWindow.setLocation(base.x + 58, base.y);
        settingsWindow.setVisible(true);
        settingsWindow.toFront();
    }

    void settingsClosed() {
        if (!gameRunning && launcherWindow != null && minimizedPins.isEmpty()) {
            launcherWindow.setVisible(false);
        }
    }

    void refreshGameState() {
        boolean running = detector.isIcarusRunning();
        if (running == gameRunning) {
            return;
        }

        gameRunning = running;
        if (gameRunning) {
            ensureLauncherLocationInitialized();
            launcherWindow.setVisible(showLauncherIcon);
            layoutMinimizedPins();
        } else {
            launcherWindow.setVisible(false);
            settingsWindow.setVisible(false);
            for (MiniPinWindow mini : minimizedPins) {
                mini.setVisible(false);
            }
        }
    }

    void beginCapture() {
        if (!gameRunning) {
            return;
        }

        final List<Window> hidden = hideOverlayWindowsForCapture();
        Timer captureDelay = new Timer(130, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ((Timer) e.getSource()).stop();
                BufferedImage screen = robot.createScreenCapture(virtualBounds);
                SelectionOverlay overlay = new SelectionOverlay(PinRecipeApp.this, screen, virtualBounds, hidden);
                overlay.setVisible(true);
            }
        });
        captureDelay.setRepeats(false);
        captureDelay.start();
    }

    void finishCapture(BufferedImage screen, Rectangle imageRect, List<Window> hiddenWindows) {
        restoreOverlayWindows(hiddenWindows);
        if (imageRect.width < 8 || imageRect.height < 8) {
            return;
        }

        imageRect = imageRect.intersection(new Rectangle(0, 0, screen.getWidth(), screen.getHeight()));
        if (imageRect.width < 8 || imageRect.height < 8) {
            return;
        }

        BufferedImage cropped = screen.getSubimage(imageRect.x, imageRect.y, imageRect.width, imageRect.height);
        PinWindow pin = new PinWindow(this, deepCopy(cropped));
        Point cursor = MouseInfo.getPointerInfo() != null ? MouseInfo.getPointerInfo().getLocation() : new Point(virtualBounds.x, virtualBounds.y);
        pin.setLocation(Math.max(virtualBounds.x, cursor.x + 18), Math.max(virtualBounds.y, cursor.y + 18));
        pins.add(pin);
        pin.setVisible(true);
    }

    void cancelCapture(List<Window> hiddenWindows) {
        restoreOverlayWindows(hiddenWindows);
    }

    void minimizePin(PinWindow pin) {
        pin.setVisible(false);
        MiniPinWindow mini = new MiniPinWindow(this, pin, minimizedPins.size() + 1);
        minimizedPins.add(mini);
        layoutMinimizedPins();
    }

    void restorePin(MiniPinWindow mini) {
        minimizedPins.remove(mini);
        mini.dispose();
        mini.getPinWindow().setVisible(true);
        mini.getPinWindow().toFront();
        layoutMinimizedPins();
    }

    void closePin(PinWindow pin) {
        pins.remove(pin);
        pin.dispose();
        for (int i = minimizedPins.size() - 1; i >= 0; i--) {
            MiniPinWindow mini = minimizedPins.get(i);
            if (mini.getPinWindow() == pin) {
                minimizedPins.remove(i);
                mini.dispose();
            }
        }
        layoutMinimizedPins();
    }

    Rectangle getVirtualBounds() {
        return virtualBounds;
    }

    void launcherMoved() {
        layoutMinimizedPins();
    }

    private void layoutMinimizedPins() {
        if (launcherWindow == null || !gameRunning) {
            return;
        }
        Point launcher = launcherWindow.getLocationOnScreenSafe();
        for (int i = 0; i < minimizedPins.size(); i++) {
            MiniPinWindow mini = minimizedPins.get(i);
            mini.setNumber(i + 1);
            mini.setLocation(launcher.x, launcher.y + 56 + (i * 42));
            mini.setVisible(true);
        }
    }

    private void ensureLauncherLocationInitialized() {
        if (launcherWindow == null) {
            return;
        }
        if (!launcherLocationInitialized) {
            launcherWindow.setLocation(virtualBounds.x + 14, virtualBounds.y + 14);
            launcherLocationInitialized = true;
        }
    }

    private List<Window> hideOverlayWindowsForCapture() {
        List<Window> hidden = new ArrayList<Window>();
        Window[] windows = Window.getWindows();
        for (Window window : windows) {
            if (window.isVisible() && window instanceof OverlayWindow) {
                hidden.add(window);
                window.setVisible(false);
            }
        }
        return hidden;
    }

    private void restoreOverlayWindows(List<Window> windows) {
        for (Window window : windows) {
            if (window.isDisplayable()) {
                window.setVisible(true);
            }
        }
        layoutMinimizedPins();
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) {
            return;
        }

        PopupMenu menu = new PopupMenu();
        showLauncherIconTrayItem = new CheckboxMenuItem("Show PinRecipe icon", showLauncherIcon);
        MenuItem settingsItem = new MenuItem("Settings");
        MenuItem exitItem = new MenuItem("Exit");

        showLauncherIconTrayItem.addItemListener(e -> setLauncherIconShown(showLauncherIconTrayItem.getState()));
        settingsItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showSettings();
            }
        });
        exitItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                shutdown();
            }
        });

        menu.add(showLauncherIconTrayItem);
        menu.add(settingsItem);
        menu.addSeparator();
        menu.add(exitItem);

        Image trayImage = pinImage.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        trayIcon = new TrayIcon(trayImage, "PinRecipe", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showSettings();
            }
        });

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void shutdown() {
        if (gamePollTimer != null) {
            gamePollTimer.stop();
        }
        hotkeyManager.stop();
        if (trayIcon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        for (Window window : Window.getWindows()) {
            window.dispose();
        }
        System.exit(0);
    }

    private static BufferedImage loadImage(String path) throws IOException {
        InputStream stream = Main.class.getResourceAsStream(path);
        if (stream == null) {
            throw new IOException("Missing resource: " + path);
        }
        try {
            return ImageIO.read(stream);
        } finally {
            stream.close();
        }
    }

    private static BufferedImage deepCopy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static boolean loadShowLauncherIconPreference() {
        try {
            return Preferences.userNodeForPackage(PinRecipeApp.class).getBoolean(SHOW_LAUNCHER_ICON_PREFERENCE, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void saveShowLauncherIconPreference(boolean shown) {
        try {
            Preferences preferences = Preferences.userNodeForPackage(PinRecipeApp.class);
            preferences.putBoolean(SHOW_LAUNCHER_ICON_PREFERENCE, shown);
            preferences.flush();
        } catch (Throwable ignored) {
        }
    }

    private static List<Image> createIconImages(BufferedImage source) {
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<Image> images = new ArrayList<Image>();
        for (int size : sizes) {
            images.add(source.getScaledInstance(size, size, Image.SCALE_SMOOTH));
        }
        images.add(source);
        return images;
    }

    private void setTaskbarIcon() {
        try {
            Class<?> taskbarClass = Class.forName("java.awt.Taskbar");
            Object taskbar = taskbarClass.getMethod("getTaskbar").invoke(null);
            taskbarClass.getMethod("setIconImage", Image.class).invoke(taskbar, pinImage);
        } catch (Throwable ignored) {
        }
    }
}

interface OverlayWindow {
}

final class LauncherWindow extends javax.swing.JWindow implements OverlayWindow {
    private final PinRecipeApp app;

    LauncherWindow(final PinRecipeApp app, BufferedImage pinImage) {
        this.app = app;
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
        setBackground(new Color(0, 0, 0, 0));
        setIconImages(app.getAppIcons());
        setSize(46, 46);

        JButton button = new JButton();
        button.setIcon(new javax.swing.ImageIcon(pinImage.getScaledInstance(36, 36, Image.SCALE_SMOOTH)));
        button.setToolTipText("PinRecipe Settings");
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        MouseAdapter dragger = new MouseAdapter() {
            private Point pressScreen;
            private Point pressWindow;
            private boolean dragged;

            @Override
            public void mousePressed(MouseEvent e) {
                pressScreen = e.getLocationOnScreen();
                pressWindow = LauncherWindow.this.getLocation();
                dragged = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressScreen == null) {
                    return;
                }
                Point screen = e.getLocationOnScreen();
                int dx = screen.x - pressScreen.x;
                int dy = screen.y - pressScreen.y;
                if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
                    dragged = true;
                }
                LauncherWindow.this.setLocation(pressWindow.x + dx, pressWindow.y + dy);
                app.launcherMoved();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!dragged) {
                    app.showSettings();
                }
                pressScreen = null;
                pressWindow = null;
            }
        };
        button.addMouseListener(dragger);
        button.addMouseMotionListener(dragger);
        setContentPane(button);
    }

    Point getLocationOnScreenSafe() {
        try {
            return getLocationOnScreen();
        } catch (java.awt.IllegalComponentStateException ex) {
            return getLocation();
        }
    }
}

final class SettingsWindow extends javax.swing.JWindow implements OverlayWindow {
    private final PinRecipeApp app;
    private final SettingsPanel panel;

    interface HotkeyRecordingTarget {
        void recordingFinished(Hotkey hotkey);
    }

    SettingsWindow(PinRecipeApp app) {
        this.app = app;
        this.panel = new SettingsPanel();
        setAlwaysOnTop(true);
        setIconImages(app.getAppIcons());
        setSize(310, 165);
        setContentPane(panel);
    }

    void refreshHotkey() {
        panel.hotkeyField.setText(app.getHotkey().toDisplayString());
        panel.recordButton.setText("Record Keybind");
    }

    void refreshLauncherIconSetting() {
        panel.showLauncherIconCheckBox.setSelected(app.isLauncherIconShown());
    }

    private final class SettingsPanel extends JPanel {
        private final JTextField hotkeyField = new JTextField();
        private final JCheckBox showLauncherIconCheckBox = new JCheckBox("Show PinRecipe icon");
        private final JButton recordButton = new JButton("Record Keybind");
        private boolean recording;

        SettingsPanel() {
            setLayout(null);
            setBackground(new Color(28, 31, 36));
            setFocusable(true);

            JLabel title = new JLabel("PinRecipe Settings");
            title.setForeground(Color.WHITE);
            title.setFont(new Font("Segoe UI", Font.BOLD, 14));
            title.setBounds(14, 8, 210, 26);
            add(title);

            JButton close = new JButton("x");
            close.setBounds(270, 8, 28, 24);
            close.setFocusable(false);
            close.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    recording = false;
                    app.cancelRecordingHotkey();
                    recordButton.setText("Record Keybind");
                    SettingsWindow.this.setVisible(false);
                    app.settingsClosed();
                }
            });
            add(close);

            JLabel label = new JLabel("Capture keybind");
            label.setForeground(new Color(210, 214, 220));
            label.setBounds(14, 48, 130, 24);
            add(label);

            hotkeyField.setEditable(false);
            hotkeyField.setBounds(130, 48, 160, 26);
            add(hotkeyField);

            showLauncherIconCheckBox.setBounds(10, 80, 190, 24);
            showLauncherIconCheckBox.setOpaque(false);
            showLauncherIconCheckBox.setForeground(new Color(210, 214, 220));
            showLauncherIconCheckBox.setFocusable(false);
            showLauncherIconCheckBox.setSelected(app.isLauncherIconShown());
            showLauncherIconCheckBox.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    app.setLauncherIconShown(showLauncherIconCheckBox.isSelected());
                }
            });
            add(showLauncherIconCheckBox);

            recordButton.setBounds(14, 116, 276, 32);
            recordButton.setFocusable(false);
            recordButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    recording = true;
                    recordButton.setText("Press new keybind...");
                    app.startRecordingHotkey(new HotkeyRecordingTarget() {
                        @Override
                        public void recordingFinished(Hotkey hotkey) {
                            recording = false;
                            hotkeyField.setText(hotkey.toDisplayString());
                            recordButton.setText("Record Keybind");
                        }
                    });
                }
            });
            add(recordButton);

            MouseAdapter dragger = new MouseAdapter() {
                private Point press;

                @Override
                public void mousePressed(MouseEvent e) {
                    press = e.getPoint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    Point screen = e.getLocationOnScreen();
                    SettingsWindow.this.setLocation(screen.x - press.x, screen.y - press.y);
                }
            };
            addMouseListener(dragger);
            addMouseMotionListener(dragger);
        }
    }
}

final class SelectionOverlay extends javax.swing.JWindow {
    private final PinRecipeApp app;
    private final BufferedImage screen;
    private final Rectangle virtualBounds;
    private final List<Window> hiddenWindows;
    private final SelectionPanel panel = new SelectionPanel();

    SelectionOverlay(PinRecipeApp app, BufferedImage screen, Rectangle virtualBounds, List<Window> hiddenWindows) {
        this.app = app;
        this.screen = screen;
        this.virtualBounds = virtualBounds;
        this.hiddenWindows = hiddenWindows;
        setAlwaysOnTop(true);
        setIconImages(app.getAppIcons());
        setBounds(virtualBounds);
        setContentPane(panel);
        setFocusableWindowState(true);
    }

    private final class SelectionPanel extends JPanel {
        private Point start;
        private Point current;

        SelectionPanel() {
            setFocusable(true);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    start = e.getPoint();
                    current = e.getPoint();
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    current = e.getPoint();
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    current = e.getPoint();
                    Rectangle local = normalizedSelection();
                    SelectionOverlay.this.dispose();
                    app.finishCapture(screen, toImageRect(local), hiddenWindows);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        SelectionOverlay.this.dispose();
                        app.cancelCapture(hiddenWindows);
                    }
                }
            });
        }

        @Override
        public void addNotify() {
            super.addNotify();
            requestFocusInWindow();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(screen, 0, 0, getWidth(), getHeight(), null);
            g2.setColor(new Color(0, 0, 0, 120));
            g2.fillRect(0, 0, getWidth(), getHeight());

            if (start != null && current != null) {
                Rectangle selection = normalizedSelection();
                Rectangle imageRect = toImageRect(selection);
                g2.drawImage(screen,
                        selection.x,
                        selection.y,
                        selection.x + selection.width,
                        selection.y + selection.height,
                        imageRect.x,
                        imageRect.y,
                        imageRect.x + imageRect.width,
                        imageRect.y + imageRect.height,
                        null);
                g2.setColor(new Color(80, 180, 255));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRect(selection.x, selection.y, Math.max(0, selection.width - 1), Math.max(0, selection.height - 1));
            }
            g2.dispose();
        }

        private Rectangle normalizedSelection() {
            int x1 = Math.min(start.x, current.x);
            int y1 = Math.min(start.y, current.y);
            int x2 = Math.max(start.x, current.x);
            int y2 = Math.max(start.y, current.y);
            return new Rectangle(x1, y1, x2 - x1, y2 - y1);
        }

        private Rectangle toImageRect(Rectangle local) {
            int panelWidth = Math.max(1, getWidth());
            int panelHeight = Math.max(1, getHeight());
            int x1 = clamp((int) Math.floor((double) local.x * screen.getWidth() / panelWidth), 0, screen.getWidth());
            int y1 = clamp((int) Math.floor((double) local.y * screen.getHeight() / panelHeight), 0, screen.getHeight());
            int x2 = clamp((int) Math.ceil((double) (local.x + local.width) * screen.getWidth() / panelWidth), 0, screen.getWidth());
            int y2 = clamp((int) Math.ceil((double) (local.y + local.height) * screen.getHeight() / panelHeight), 0, screen.getHeight());
            return new Rectangle(x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1));
        }

        private int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}

final class PinWindow extends javax.swing.JWindow implements OverlayWindow {
    private static final int HEADER_HEIGHT = 24;
    private static final int RESIZE_SIZE = 18;
    private static final int MIN_IMAGE_WIDTH = 120;

    private final PinRecipeApp app;
    private final BufferedImage image;
    private final double aspect;
    private final PinPanel panel = new PinPanel();
    private int imageWidth;
    private int imageHeight;

    PinWindow(PinRecipeApp app, BufferedImage image) {
        this.app = app;
        this.image = image;
        this.aspect = (double) image.getWidth() / Math.max(1, image.getHeight());
        imageWidth = Math.max(MIN_IMAGE_WIDTH, Math.min(image.getWidth(), 520));
        imageHeight = Math.max(1, (int) Math.round(imageWidth / aspect));
        setAlwaysOnTop(true);
        setBackground(new Color(0, 0, 0, 0));
        setIconImages(app.getAppIcons());
        setContentPane(panel);
        applySize();
    }

    BufferedImage getImage() {
        return image;
    }

    private void applySize() {
        setSize(imageWidth, imageHeight + HEADER_HEIGHT);
        panel.setPreferredSize(new Dimension(imageWidth, imageHeight + HEADER_HEIGHT));
        revalidate();
        repaint();
    }

    private final class PinPanel extends JPanel {
        private Point pressScreen;
        private Point pressWindow;
        private int pressWidth;
        private boolean resizing;

        PinPanel() {
            setOpaque(false);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (closeRect().contains(e.getPoint())) {
                        app.closePin(PinWindow.this);
                        return;
                    }
                    if (minRect().contains(e.getPoint())) {
                        app.minimizePin(PinWindow.this);
                        return;
                    }
                    pressScreen = e.getLocationOnScreen();
                    pressWindow = PinWindow.this.getLocation();
                    pressWidth = imageWidth;
                    resizing = resizeRect().contains(e.getPoint());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (pressScreen == null) {
                        return;
                    }
                    Point screen = e.getLocationOnScreen();
                    if (resizing) {
                        int dx = screen.x - pressScreen.x;
                        int dyWidth = (int) Math.round((screen.y - pressScreen.y) * aspect);
                        imageWidth = Math.max(MIN_IMAGE_WIDTH, pressWidth + Math.max(dx, dyWidth));
                        imageHeight = Math.max(1, (int) Math.round(imageWidth / aspect));
                        applySize();
                    } else {
                        PinWindow.this.setLocation(pressWindow.x + screen.x - pressScreen.x, pressWindow.y + screen.y - pressScreen.y);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    pressScreen = null;
                    resizing = false;
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setColor(new Color(22, 25, 29, 235));
            g2.fillRect(0, 0, getWidth(), HEADER_HEIGHT);
            g2.setColor(new Color(8, 10, 12, 210));
            g2.fillRect(0, HEADER_HEIGHT, getWidth(), getHeight() - HEADER_HEIGHT);
            g2.drawImage(image, 0, HEADER_HEIGHT, imageWidth, imageHeight, null);

            drawWindowButton(g2, minRect(), "_");
            drawWindowButton(g2, closeRect(), "x");

            int x = getWidth();
            int y = getHeight();
            g2.setColor(new Color(80, 180, 255, 190));
            int[] xs = {x - RESIZE_SIZE, x, x};
            int[] ys = {y, y - RESIZE_SIZE, y};
            g2.fillPolygon(xs, ys, 3);
            g2.dispose();
        }

        private void drawWindowButton(Graphics2D g2, Rectangle rect, String text) {
            g2.setColor(new Color(45, 50, 56));
            g2.fillRect(rect.x, rect.y, rect.width, rect.height);
            g2.setColor(Color.WHITE);
            FontMetrics metrics = g2.getFontMetrics();
            int tx = rect.x + (rect.width - metrics.stringWidth(text)) / 2;
            int ty = rect.y + ((rect.height - metrics.getHeight()) / 2) + metrics.getAscent();
            g2.drawString(text, tx, ty);
        }

        private Rectangle minRect() {
            return new Rectangle(getWidth() - 52, 3, 22, 18);
        }

        private Rectangle closeRect() {
            return new Rectangle(getWidth() - 27, 3, 22, 18);
        }

        private Rectangle resizeRect() {
            return new Rectangle(getWidth() - RESIZE_SIZE, getHeight() - RESIZE_SIZE, RESIZE_SIZE, RESIZE_SIZE);
        }
    }
}

final class MiniPinWindow extends javax.swing.JWindow implements OverlayWindow {
    private final PinRecipeApp app;
    private final PinWindow pinWindow;
    private final MiniPanel panel = new MiniPanel();
    private int number;

    MiniPinWindow(PinRecipeApp app, PinWindow pinWindow, int number) {
        this.app = app;
        this.pinWindow = pinWindow;
        this.number = number;
        setAlwaysOnTop(true);
        setBackground(new Color(0, 0, 0, 0));
        setIconImages(app.getAppIcons());
        setSize(42, 38);
        setContentPane(panel);
    }

    PinWindow getPinWindow() {
        return pinWindow;
    }

    void setNumber(int number) {
        this.number = number;
        repaint();
    }

    private final class MiniPanel extends JPanel {
        MiniPanel() {
            setOpaque(false);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    app.restorePin(MiniPinWindow.this);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setColor(new Color(22, 25, 29, 225));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g2.drawImage(pinWindow.getImage(), 4, 4, 34, 30, null);
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillOval(22, 2, 18, 18);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
            String text = String.valueOf(number);
            FontMetrics metrics = g2.getFontMetrics();
            g2.drawString(text, 31 - metrics.stringWidth(text) / 2, 15);
            g2.dispose();
        }
    }
}

final class Hotkey {
    static final int MOD_ALT = 0x0001;
    static final int MOD_CONTROL = 0x0002;
    static final int MOD_SHIFT = 0x0004;
    static final int MOD_WIN = 0x0008;

    final int vkCode;
    final int modifiers;

    private Hotkey(int vkCode, int modifiers) {
        this.vkCode = vkCode;
        this.modifiers = modifiers;
    }

    static Hotkey f8() {
        return new Hotkey(KeyEvent.VK_F8, 0);
    }

    static Hotkey fromGlobalKey(int vkCode) {
        return new Hotkey(vkCode, currentModifiers());
    }

    static int currentModifiers() {
        int mods = 0;
        if (User32.isKeyDown(KeyEvent.VK_CONTROL)) {
            mods |= MOD_CONTROL;
        }
        if (User32.isKeyDown(KeyEvent.VK_SHIFT)) {
            mods |= MOD_SHIFT;
        }
        if (User32.isKeyDown(KeyEvent.VK_ALT)) {
            mods |= MOD_ALT;
        }
        if (User32.isKeyDown(0x5B) || User32.isKeyDown(0x5C)) {
            mods |= MOD_WIN;
        }
        return mods;
    }

    boolean matches(int vkCode, int activeModifiers) {
        return this.vkCode == vkCode && this.modifiers == activeModifiers;
    }

    String toDisplayString() {
        List<String> parts = new ArrayList<String>();
        if ((modifiers & MOD_CONTROL) != 0) {
            parts.add("Ctrl");
        }
        if ((modifiers & MOD_SHIFT) != 0) {
            parts.add("Shift");
        }
        if ((modifiers & MOD_ALT) != 0) {
            parts.add("Alt");
        }
        if ((modifiers & MOD_WIN) != 0) {
            parts.add("Win");
        }
        parts.add(KeyEvent.getKeyText(vkCode));
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder.append("+");
            }
            builder.append(parts.get(i));
        }
        return builder.toString();
    }
}

final class HotkeyManager {
    interface Recorder {
        void recorded(Hotkey hotkey);
    }

    private KeyboardPollThread thread;
    private volatile Hotkey hotkey;
    private volatile Runnable callback;
    private volatile Recorder recorder;
    private volatile boolean keyboardWatcherRunning;

    synchronized void start(Hotkey hotkey, Runnable callback) {
        this.hotkey = hotkey;
        this.callback = callback;
        if (thread == null || !thread.isAlive()) {
            keyboardWatcherRunning = false;
            thread = new KeyboardPollThread(this);
            thread.setDaemon(true);
            thread.setName("PinRecipe-KeyboardWatcher");
            thread.start();
        }
    }

    void setHotkey(Hotkey hotkey) {
        this.hotkey = hotkey;
    }

    void startRecording(Recorder recorder) {
        this.recorder = recorder;
    }

    void cancelRecording() {
        this.recorder = null;
    }

    boolean isKeyboardWatcherRunning() {
        return keyboardWatcherRunning;
    }

    synchronized void stop() {
        if (thread != null) {
            KeyboardPollThread previous = thread;
            previous.requestStop();
            try {
                previous.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
            keyboardWatcherRunning = false;
        }
    }

    void handleKeyDown(int vkCode) {
        if (isModifierKey(vkCode)) {
            return;
        }

        Recorder activeRecorder = recorder;
        if (activeRecorder != null) {
            recorder = null;
            activeRecorder.recorded(Hotkey.fromGlobalKey(vkCode));
            return;
        }

        Hotkey activeHotkey = hotkey;
        Runnable activeCallback = callback;
        if (activeHotkey != null && activeCallback != null && activeHotkey.matches(vkCode, Hotkey.currentModifiers())) {
            SwingUtilities.invokeLater(activeCallback);
        }
    }

    private static boolean isModifierKey(int vkCode) {
        return vkCode == KeyEvent.VK_SHIFT
                || vkCode == KeyEvent.VK_CONTROL
                || vkCode == KeyEvent.VK_ALT
                || vkCode == 0x5B
                || vkCode == 0x5C;
    }

    private static final class KeyboardPollThread extends Thread {
        private final HotkeyManager manager;
        private final Set<Integer> downKeys = new HashSet<Integer>();
        private volatile boolean running = true;

        KeyboardPollThread(final HotkeyManager manager) {
            this.manager = manager;
        }

        @Override
        public void run() {
            manager.keyboardWatcherRunning = true;
            try {
                while (running) {
                    pollKeys();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                    }
                }
            } finally {
                manager.keyboardWatcherRunning = false;
                downKeys.clear();
            }
        }

        private void pollKeys() {
            for (int vkCode = 1; vkCode <= 254; vkCode++) {
                boolean isDown = User32.isKeyDown(vkCode);
                if (isDown) {
                    if (!downKeys.contains(vkCode)) {
                        downKeys.add(vkCode);
                        manager.handleKeyDown(vkCode);
                    }
                } else {
                    downKeys.remove(vkCode);
                }
            }
        }

        void requestStop() {
            running = false;
            interrupt();
        }
    }
}

interface User32 extends StdCallLibrary {
    User32 INSTANCE = Native.load("user32", User32.class);

    short GetAsyncKeyState(int vKey);

    static boolean isKeyDown(int vKey) {
        return (INSTANCE.GetAsyncKeyState(vKey) & 0x8000) != 0;
    }
}

interface Kernel32 extends StdCallLibrary {
    Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

    int GetCurrentThreadId();
}

final class DpiUtil {
    private static final int PROCESS_PER_MONITOR_DPI_AWARE = 2;
    private static final int E_ACCESSDENIED = 0x80070005;

    private DpiUtil() {
    }

    static void enableDpiAwareness() {
        System.setProperty("sun.java2d.dpiaware", "true");

        if (setProcessDpiAwarenessContext()) {
            return;
        }
        if (setProcessDpiAwareness()) {
            return;
        }
        setProcessDpiAware();
    }

    private static boolean setProcessDpiAwarenessContext() {
        try {
            return User32Dpi.INSTANCE.SetProcessDpiAwarenessContext(Pointer.createConstant(-4));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setProcessDpiAwareness() {
        try {
            int result = Shcore.INSTANCE.SetProcessDpiAwareness(PROCESS_PER_MONITOR_DPI_AWARE);
            return result == 0 || result == E_ACCESSDENIED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setProcessDpiAware() {
        try {
            User32Dpi.INSTANCE.SetProcessDPIAware();
        } catch (Throwable ignored) {
        }
    }

    interface User32Dpi extends StdCallLibrary {
        User32Dpi INSTANCE = Native.load("user32", User32Dpi.class);

        boolean SetProcessDpiAwarenessContext(Pointer dpiContext);

        boolean SetProcessDPIAware();
    }

    interface Shcore extends StdCallLibrary {
        Shcore INSTANCE = Native.load("Shcore", Shcore.class);

        int SetProcessDpiAwareness(int value);
    }
}

final class IcarusDetector {
    private static final String[] PROCESS_NAMES = {"Icarus.exe", "Icarus-Win64-Shipping.exe"};

    boolean isIcarusRunning() {
        for (String processName : PROCESS_NAMES) {
            if (isProcessRunning(processName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isProcessRunning(String processName) {
        Process process = null;
        try {
            process = new ProcessBuilder("tasklist.exe", "/NH", "/FI", "IMAGENAME eq " + processName).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase().contains(processName.toLowerCase())) {
                    return true;
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return false;
    }
}

final class DisplayUtil {
    private DisplayUtil() {
    }

    static Rectangle getVirtualBounds() {
        Rectangle bounds = new Rectangle();
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] devices = environment.getScreenDevices();
        for (GraphicsDevice device : devices) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            bounds = bounds.union(configuration.getBounds());
        }
        return bounds;
    }
}
