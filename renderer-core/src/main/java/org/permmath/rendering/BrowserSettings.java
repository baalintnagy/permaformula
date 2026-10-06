package org.permmath.rendering;

import java.nio.file.Path;
import java.time.Duration;

public record BrowserSettings(Path browserDirectory, Duration timeout, boolean chromiumSandbox) {}
