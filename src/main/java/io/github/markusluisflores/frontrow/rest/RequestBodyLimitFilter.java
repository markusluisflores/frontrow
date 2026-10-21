package io.github.markusluisflores.frontrow.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds every request body read through getInputStream() or getReader(), ahead of every security chain (plan 4A
 * decision 8). The services copy what they receive — HoldCommand copies its whole seat list — so the bound belongs at
 * the edge. A declared Content-Length over the limit is refused before anything is read. A chunked body is counted as
 * it is read, through the stream or the reader, and crossing the limit fails the read.
 *
 * <p>So the bound on every path depends on the parsers that read a body for the application staying off, as both are in
 * application.yml: Spring's multipart resolver has Tomcat parse the body from its own buffer, never through this
 * wrapper; and Boot's FormContentFilter reads a form body before security answers, so an oversized one became an
 * anonymous 500. Re-enabling either reopens the hole.
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    public static final int MAX_BODY_BYTES = 16 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            Problems.write(request, response, Problems.plain(HttpStatus.CONTENT_TOO_LARGE));
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    /** Thrown by a read that crosses the limit; RestExceptionHandler answers it with 413. */
    public static final class BodyTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLargeException() {
            super("request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        /** Kept on the request, so a caller that asks for the stream again cannot reset it. */
        private long count;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream());
        }

        /** The reader decodes the limited stream, so a body read as characters is bounded the same way. */
        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset;
            try {
                charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException unknown) {
                // The servlet contract: an encoding the JVM can't decode is a checked UnsupportedEncodingException.
                throw new UnsupportedEncodingException(encoding);
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        private void counted(int n) throws BodyTooLargeException {
            count += n;
            if (count > MAX_BODY_BYTES) {
                throw new BodyTooLargeException();
            }
        }

        private final class LimitedInputStream extends ServletInputStream {

            private final ServletInputStream delegate;

            LimitedInputStream(ServletInputStream delegate) {
                this.delegate = delegate;
            }

            @Override
            public int read() throws IOException {
                int b = delegate.read();
                if (b >= 0) {
                    counted(1);
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = delegate.read(buffer, offset, length);
                if (n > 0) {
                    counted(n);
                }
                return n;
            }

            @Override
            public boolean isFinished() {
                return delegate.isFinished();
            }

            @Override
            public boolean isReady() {
                return delegate.isReady();
            }

            @Override
            public void setReadListener(ReadListener listener) {
                delegate.setReadListener(listener);
            }
        }
    }
}
