package io.jenkins.plugins.wiz;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a validated and parsed Wiz CLI download URL with version information.
 */
public class ParsedWizCliUrl {
    private static final Pattern VERSION_PATH_PATTERN = Pattern.compile("/wizcli/([^/]+)/");

    private final String url;
    private final WizCliVersion version;
    private final boolean latest;

    /**
     * Creates a new ParsedWizCliUrl instance.
     *
     * @param url the validated URL string
     * @param version the detected CLI version
     */
    public ParsedWizCliUrl(String url, WizCliVersion version) {
        this.url = url;
        this.version = version;
        this.latest = detectLatest(url);
    }

    private static boolean detectLatest(String url) {
        Matcher matcher = VERSION_PATH_PATTERN.matcher(url);
        if (matcher.find()) {
            return "latest".equals(matcher.group(1));
        }
        return false;
    }

    /**
     * Gets the CLI version detected from the URL.
     *
     * @return the CLI version
     */
    public WizCliVersion getVersion() {
        return version;
    }

    /**
     * Gets the URL as a string.
     *
     * @return the URL string
     */
    public String getUrl() {
        return url;
    }

    /**
     * Checks if this URL points to the latest version of the CLI.
     *
     * @return true if the URL contains "latest" in the version path, false otherwise
     */
    public boolean isLatest() {
        return latest;
    }

    /**
     * Returns the URL string representation.
     *
     * @return the URL string
     */
    @Override
    public String toString() {
        return url;
    }
}
