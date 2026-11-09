package io.github.markusluisflores.frontrow.mcp;

import io.github.markusluisflores.frontrow.rest.Problems;
import io.github.markusluisflores.frontrow.rest.RequestBodyLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plan 5 task 9b: answers, as a JSON-RPC error, the two JSON-RPC request shapes the SDK's Streamable HTTP transport
 * leaves without a usable answer. It sits after the /mcp chain's authorization check, so only an authenticated agent
 * reaches it.
 *
 * <ul>
 *   <li>A request whose {@code params} is present but is not a JSON object (an array, a string, a number): the
 *       transport fails to bind it while its SSE answer is being produced, and the failure surfaces as a bare 500
 *       Problem Details through RestExceptionHandler's catch-all, not as a JSON-RPC answer. Answered here with -32602.
 *       A tools/call with no params member at all is the same failure and gets the same answer.
 *   <li>A request whose method the server does not serve: the SDK writes a "Method not found" event to the SSE stream
 *       and never closes it, so a client waiting for the answer to finish waits forever. Answered here with -32601 and
 *       a body that ends.
 * </ul>
 *
 * Everything else (a notification, a response, malformed JSON, a form body) is replayed untouched to the transport.
 * The body is read through the request-body limit's counting stream, so the 16 KiB bound still applies. The message
 * names no exception and carries no stack trace.
 */
public final class McpRequestShapeFilter extends OncePerRequestFilter {

    /**
     * The client-to-server requests the SDK server registers a handler for. McpUnanswerableRequestsTest pins that each
     * one still completes (it catches a method the SDK drops, not one it adds: look at this list on an SDK upgrade).
     */
    static final Set<String> SERVED_METHODS = Set.of(
            "initialize",
            "ping",
            "tools/list",
            "tools/call",
            "resources/list",
            "resources/templates/list",
            "resources/read",
            "prompts/list",
            "prompts/get",
            "completion/complete",
            "logging/setLevel");

    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isJsonPost(request)) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body;
        try {
            body = request.getInputStream().readAllBytes();
        } catch (RequestBodyLimitFilter.BodyTooLargeException tooLarge) {
            Problems.write(request, response, Problems.plain(HttpStatus.CONTENT_TOO_LARGE));
            return;
        }
        JsonNode message = parse(body);
        if (message != null && message.isObject() && isRequest(message)) {
            String method = message.get("method").asString();
            JsonNode params = message.get("params");
            if (!SERVED_METHODS.contains(method)) {
                // HTTP 200: a JSON-RPC error is a reply to a delivered request, unlike this chain's 4xx transport
                // errors.
                answer(response, message.get("id"), METHOD_NOT_FOUND, "Method not found");
                return;
            }
            boolean callWithoutParams = method.equals("tools/call") && params == null;
            if (callWithoutParams || (params != null && !params.isObject())) {
                answer(response, message.get("id"), INVALID_PARAMS, "Invalid params: expected an object");
                return;
            }
        }
        chain.doFilter(new ReplayedBody(request, body), response);
    }

    /** A JSON-RPC request: a string method and an id that is a string or a number (a notification has no id). */
    private static boolean isRequest(JsonNode message) {
        JsonNode id = message.get("id");
        JsonNode method = message.get("method");
        return method != null && method.isString() && id != null && (id.isString() || id.isNumber());
    }

    private static JsonNode parse(byte[] body) {
        try {
            return JSON.readTree(body);
        } catch (JacksonException malformed) {
            return null; // the transport answers malformed JSON itself
        }
    }

    private static void answer(HttpServletResponse response, JsonNode id, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ObjectNode error = JSON.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        ObjectNode reply = JSON.createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.set("id", id);
        reply.set("error", error);
        response.getWriter().write(JSON.writeValueAsString(reply));
    }

    private static boolean isJsonPost(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod()) || request.getContentType() == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()));
        } catch (InvalidMediaTypeException unparseable) {
            return false;
        }
    }

    /** The body already read, handed on to the transport as if it had not been. */
    private static final class ReplayedBody extends HttpServletRequestWrapper {

        private final byte[] body;

        ReplayedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("blocking reads only");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }

        private Charset charset() {
            String encoding = getCharacterEncoding();
            if (encoding != null) {
                try {
                    return Charset.forName(encoding);
                } catch (IllegalArgumentException unknown) {
                    // JSON is UTF-8 (RFC 8259)
                }
            }
            return StandardCharsets.UTF_8;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
