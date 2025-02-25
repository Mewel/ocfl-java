/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019-2021 University of Wisconsin Board of Regents
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.ocfl.api.io;

import io.ocfl.api.exception.FixityCheckException;
import io.ocfl.api.model.DigestAlgorithm;
import io.ocfl.api.util.Enforce;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.util.Optional;

/**
 * Wrapper around Java's DigestInputStream to compute a digest while streaming data, and then verify the fixity of the data.
 * After all of the stream's bytes have been read, {@code checkFixity()} should be called.
 */
public class FixityCheckInputStream extends DigestInputStream {

    private boolean enabled = true;
    private final DigestAlgorithm digestAlgorithm;
    private final String expectedDigestValue;
    private String actualDigestValue;

    /**
     * @param inputStream the underlying stream
     * @param digestAlgorithm the algorithm to use to calculate the digest (eg. sha512)
     * @param expectedDigestValue the expected digest value
     */
    public FixityCheckInputStream(
            InputStream inputStream, DigestAlgorithm digestAlgorithm, String expectedDigestValue) {
        super(inputStream, digestAlgorithm.getMessageDigest());
        this.digestAlgorithm = Enforce.notNull(digestAlgorithm, "digestAlgorithm cannot be null");
        this.expectedDigestValue = Enforce.notBlank(expectedDigestValue, "expectedDigestValue cannot be blank");
    }

    /**
     * Performs a fixity check and throws an exception if the check fails. This method MUST NOT be called before the
     * entire stream has been read, doing so will invalidate the digest.
     *
     * <p>If the check is disabled, nothing happens
     *
     * @throws FixityCheckException when the actual digest value does not match the expected value
     */
    public void checkFixity() {
        if (enabled) {
            var actualDigest = getActualDigestValue().get();
            if (!expectedDigestValue.equalsIgnoreCase(actualDigest)) {
                throw new FixityCheckException(String.format(
                        "Expected %s digest: %s; Actual: %s",
                        digest.getAlgorithm(), expectedDigestValue, actualDigest));
            }
        }
    }

    /**
     * @return the expected digest value
     */
    public String getExpectedDigestValue() {
        return expectedDigestValue;
    }

    /**
     * Returns the hex encoded digest value of the input stream. This method MUST NOT be called before the entire
     * stream has been read, doing so will invalidate the digest. A digest will not be returned if the fixity check
     * is disabled.
     *
     * @return the digest of the stream
     */
    public Optional<String> getActualDigestValue() {
        if (enabled && actualDigestValue == null) {
            actualDigestValue = digestAlgorithm.encode(digest.digest());
        }
        return Optional.of(actualDigestValue);
    }

    /**
     * By default fixity checking is enabled. Use this method to disable it, and prevent needless digest computation
     *
     * @param enabled if fixity should be checked
     * @return this stream
     */
    public FixityCheckInputStream enableFixityCheck(boolean enabled) {
        on(enabled);
        return this;
    }

    @Override
    public void on(boolean on) {
        enabled = on;
        super.on(on);
    }

    @Override
    public String toString() {
        return "[Fixity Check Input Stream] expected: " + expectedDigestValue + "; actual: " + digest.toString();
    }
}
