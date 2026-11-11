package io.github.houseelves.github.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsCapTest {

    @TempDir
    Path dir;

    @Test
    void theCapDefaultsToThreeAndStaysBetweenZeroAndFifty() throws Exception {
        Settings s = new Settings(dir);
        assertEquals(Settings.DEFAULT_MAX_OPEN_PRS, s.maxOpenPrs());
        s.setMaxOpenPrs(0);
        assertEquals(0, new Settings(dir).maxOpenPrs()); // kept
        s.setMaxOpenPrs(7);
        assertEquals(7, s.maxOpenPrs());
        assertThrows(IllegalArgumentException.class, () -> s.setMaxOpenPrs(-1));
        assertThrows(IllegalArgumentException.class, () -> s.setMaxOpenPrs(51));
        Files.writeString(dir.resolve("settings.json"), "{\"" + Settings.MAX_OPEN_PRS + "\": \"lots\"}");
        assertEquals(Settings.DEFAULT_MAX_OPEN_PRS, new Settings(dir).maxOpenPrs()); // unreadable: the default
    }
}
