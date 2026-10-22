package io.github.houseelves.github.elf;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * The elf's log: stderr, which the runner keeps in the elf's log. stdout is the action's answer, so nothing else
 * goes there. Debug lines only with GITHUB_ELF_DEBUG set.
 */
public final class Log {

    private static final boolean DEBUG = System.getenv("GITHUB_ELF_DEBUG") != null;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String name;

    private Log(String name) {
        this.name = name;
    }

    public static Log of(Class<?> c) {
        return new Log(c.getSimpleName());
    }

    public void info(String text) {
        line("INFO", text);
    }

    public void warn(String text) {
        line("WARN", text);
    }

    public void infof(String format, Object... args) {
        line("INFO", String.format(format, args));
    }

    public void warnf(String format, Object... args) {
        line("WARN", String.format(format, args));
    }

    public void debugf(String format, Object... args) {
        if (DEBUG) line("DEBUG", String.format(format, args));
    }

    private void line(String level, String text) {
        System.err.println(LocalTime.now().format(TIME) + " " + level + " [" + name + "] " + text);
    }
}
