package io.jenkins.plugins.wiz;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ParsedWizCliUrlTest {

    private final String url;
    private final boolean expectedIsLatest;

    public ParsedWizCliUrlTest(String url, boolean expectedIsLatest) {
        this.url = url;
        this.expectedIsLatest = expectedIsLatest;
    }

    @Parameters(name = "{index}: {0} -> isLatest={1}")
    public static Collection<Object[]> testCases() {
        return Arrays.asList(new Object[][] {
            // Latest URLs should return true
            {"https://downloads.wiz.io/v1/wizcli/latest/wizcli-linux-amd64", true},
            {"https://downloads.wiz.io/v1/wizcli/latest/wizcli-darwin-amd64", true},
            {"https://downloads.wiz.io/v1/wizcli/latest/wizcli-windows-amd64.exe", true},
            {"https://downloads.wiz.io/wizcli/latest/wizcli-linux-amd64", true},
            {"https://downloads.wiz.io/wizcli/latest/wizcli-darwin-amd64", true},
            {"https://downloads.wiz.io/wizcli/latest/wizcli-windows-amd64.exe", true},

            // Specific version URLs should return false
            {"https://downloads.wiz.io/v1/wizcli/1.0.0/wizcli-linux-amd64", false},
            {"https://downloads.wiz.io/v1/wizcli/1.2.3/wizcli-darwin-amd64", false},
            {"https://downloads.wiz.io/v1/wizcli/0.9.0/wizcli-windows-amd64.exe", false},
            {"https://downloads.wiz.io/wizcli/1.0.0/wizcli-linux-amd64", false},
            {"https://downloads.wiz.io/wizcli/1.0.2/wizcli-linux-amd64", false},
            {"https://downloads.wiz.io/wizcli/0.0.1/wizcli-linux-amd64", false},
            {"https://downloads.wiz.io/wizcli/0.1.0/wizcli-windows-amd64.exe", false},
        });
    }

    @Test
    public void testIsLatestDetection() {
        try {
            ParsedWizCliUrl parsedUrl = WizInputValidator.parseWizCliUrl(url);
            assertEquals("isLatest() detection failed for URL: " + url, expectedIsLatest, parsedUrl.isLatest());
        } catch (Exception e) {
            fail("Failed to parse valid URL: " + url + ", error: " + e.getMessage());
        }
    }
}
