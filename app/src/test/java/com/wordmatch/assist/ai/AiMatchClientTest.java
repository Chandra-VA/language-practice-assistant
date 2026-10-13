package com.wordmatch.assist.ai;

import org.json.*;
import org.junit.Test;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public final class AiMatchClientTest {
    private static final List<String> LEFT = Arrays.asList("苹果", "车费");
    private static final List<String> RIGHT = Arrays.asList("fare", "apple");
    private static final String PAIRS = "{\"pairs\":[{\"left\":\"苹果\",\"right\":\"apple\",\"confidence\":0.99},{\"left\":\"车费\",\"right\":\"fare\",\"confidence\":0.98}]}";
    private AiMatchSettings.Settings settings(String provider) {
        return AiMatchSettings.createTransient(provider,"https://unit.test/chat/completions","unit-model","test-secret",1000);
    }
    private String response(Object content) throws Exception {
        return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("content",content)))).toString();
    }
    @Test public void exerciseChoiceUsesCurrentIdsAndStructuredDataOnly() throws Exception {
        com.wordmatch.assist.exercise.ExercisePage page = new com.wordmatch.assist.exercise.ExercisePage();
        page.type = com.wordmatch.assist.exercise.ExerciseType.CHOICE; page.mode = com.wordmatch.assist.exercise.ExercisePage.Mode.CHOICE;
        page.instruction = "选择正确的翻译"; page.prompt = "苹果";
        for (String word : Arrays.asList("orange", "apple")) { com.wordmatch.assist.sentence.SentencePage.Node n = new com.wordmatch.assist.sentence.SentencePage.Node(); n.text = word; page.options.add(n); }
        Fake connection = new Fake(200,response("{\"choice_ids\":[1],\"confidence\":0.99}"));
        AiMatchClient.SentenceResult result = new AiMatchClient(url -> connection).exercise(settings("deepseek"),page,new AiMatchClient.Call());
        assertTrue(result.error,result.error.isEmpty()); assertEquals(Arrays.asList("apple"),result.words);
        JSONObject request = new JSONObject(connection.sent.toString("UTF-8"));
        JSONObject data = new JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));
        assertEquals("苹果",data.getString("question"));assertEquals(2,data.getJSONArray("options").length());assertEquals(0,data.getInt("field_count"));
        assertFalse(data.has("screenshot"));assertFalse(data.has("key"));assertEquals("disabled",request.getJSONObject("thinking").getString("type"));
    }
    @Test public void exerciseHttpFailureAndCancelledCallNeverYieldAnAnswer() throws Exception {
        com.wordmatch.assist.exercise.ExercisePage page = new com.wordmatch.assist.exercise.ExercisePage();
        page.type = com.wordmatch.assist.exercise.ExerciseType.TEXT;page.mode = com.wordmatch.assist.exercise.ExercisePage.Mode.TEXT;
        Fake connection = new Fake(401,"{\"error\":{\"message\":\"test-secret\"}}");
        AiMatchClient.SentenceResult result = new AiMatchClient(url -> connection).exercise(settings("compatible"),page,new AiMatchClient.Call());
        assertTrue(result.words.isEmpty());assertTrue(result.error.contains("401"));assertFalse(result.error.contains("test-secret"));
        AiMatchClient.Call call = new AiMatchClient.Call();call.cancel();
        result = new AiMatchClient(url -> {throw new AssertionError("cancelled request must not open a connection");}).exercise(settings("compatible"),page,call);
        assertTrue(result.words.isEmpty());assertFalse(result.error.isEmpty());
    }
    @Test public void realJsonRequestAndShuffledResponseRoundTrip() throws Exception {
        Fake connection = new Fake(200,response(PAIRS));
        AiMatchClient.Result result = new AiMatchClient(url -> connection).match(settings("qwen"),LEFT,RIGHT);
        assertTrue(result.getError(),result.isSuccess());
        assertEquals(2,result.getPairs().size());
        assertEquals("apple",result.getPairs().get(0).getRight());
        com.wordmatch.assist.matching.PairMatcher matcher = new com.wordmatch.assist.matching.PairMatcher((a,b) -> {
            for (AiPairValidator.ValidatedPair pair : result.getPairs()) {
                if (pair.getLeft().equals(a) && pair.getRight().equals(b)) return 1.0;
            }
            return 0.0;
        });
        List<com.wordmatch.assist.model.WordBox> words = Arrays.asList(
                new com.wordmatch.assist.model.WordBox("苹果",10,200,80,240),
                new com.wordmatch.assist.model.WordBox("车费",10,300,80,340),
                new com.wordmatch.assist.model.WordBox("fare",700,200,780,240),
                new com.wordmatch.assist.model.WordBox("apple",700,300,780,340));
        assertEquals(2, matcher.match(words,1000).size());
        JSONObject request = new JSONObject(connection.sent.toString("UTF-8"));
        assertFalse(request.getBoolean("enable_thinking"));
        assertEquals("json_object",request.getJSONObject("response_format").getString("type"));
        assertEquals("Bearer test-secret",connection.getRequestProperty("Authorization"));
        assertFalse(connection.getInstanceFollowRedirects());
        assertTrue(connection.disconnected);
    }
    @Test public void deepseekAndGenericGatewayParametersRemainCompatible() throws Exception {
        for (String provider : Arrays.asList("deepseek","compatible")) {
            Fake connection = new Fake(200,response("```json\n"+PAIRS+"\n```"));
            assertTrue(new AiMatchClient(url -> connection).match(settings(provider),LEFT,RIGHT).isSuccess());
            JSONObject request = new JSONObject(connection.sent.toString("UTF-8"));
            if (provider.equals("deepseek")) assertEquals("disabled",request.getJSONObject("thinking").getString("type"));
            else { assertFalse(request.has("thinking")); assertFalse(request.has("response_format")); }
        }
    }
    @Test public void contentBlocksAreParsedButMalformedAndUntrustedPairsAreRejected() throws Exception {
        Fake blocks = new Fake(200,response(new JSONArray().put(new JSONObject().put("text",PAIRS))));
        assertTrue(new AiMatchClient(url -> blocks).match(settings("compatible"),LEFT,RIGHT).isSuccess());
        for (String payload : Arrays.asList("not json", "{\"pairs\":[{\"left\":\"不存在\",\"right\":\"apple\",\"confidence\":1}]}")) {
            Fake invalid = new Fake(200,response(payload));
            assertFalse(new AiMatchClient(url -> invalid).match(settings("compatible"),LEFT,RIGHT).isSuccess());
        }
    }
    @Test public void httpErrorsRedactCredentialsAndNeverBecomeSuccess() throws Exception {
        Fake error = new Fake(401,"{\"error\":{\"message\":\"bad test-secret Bearer hidden-token\"}}");
        String detail = new AiMatchClient(url -> error).match(settings("compatible"),LEFT,RIGHT).getError();
        assertTrue(detail.contains("401"));
        assertFalse(detail.contains("test-secret"));
        assertFalse(detail.contains("hidden-token"));
        Fake redirect = new Fake(302,"");
        assertTrue(new AiMatchClient(url -> redirect).match(settings("compatible"),LEFT,RIGHT).getError().contains("302"));
    }
    @Test public void cancelledCallNeverOpensConnection() {
        AiMatchClient.Call call = new AiMatchClient.Call(); call.cancel();
        AiMatchClient.Result result = new AiMatchClient(url -> { throw new AssertionError("opened cancelled request"); })
                .match(settings("compatible"),LEFT,RIGHT,call);
        assertTrue(result.getError().contains("取消"));
    }
    @Test public void cancellationDisconnectsBlockedRequestAndFreesWorker() throws Exception {
        Fake blocked = new Fake(200,""); blocked.block = true;
        AiMatchClient.Call call = new AiMatchClient.Call();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AiMatchClient.Result> result = executor.submit(() -> new AiMatchClient(url -> blocked).match(settings("compatible"),LEFT,RIGHT,call));
            assertTrue(blocked.entered.await(1,TimeUnit.SECONDS));
            call.cancel();
            assertTrue(result.get(1,TimeUnit.SECONDS).getError().contains("取消"));
            assertTrue(blocked.disconnected);
            assertEquals("next",executor.submit(() -> "next").get(1,TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    @Test public void wallClockDeadlineDisconnectsAnUnresponsiveServer() throws Exception {
        Fake blocked = new Fake(200,""); blocked.block = true;
        long started = System.nanoTime();
        AiMatchClient.Result result = new AiMatchClient(url -> blocked).match(settings("compatible"),LEFT,RIGHT);
        assertTrue(result.getError(), result.getError().contains("超时"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started) < 2500);
        assertTrue(blocked.disconnected);
    }
    @Test public void sentenceTransportUsesExactTokenIdsAndExistingProviderConfiguration() throws Exception {
        Fake connection = new Fake(200,response("{\"order\":[1,0],\"confidence\":0.99}"));
        AiMatchClient.SentenceResult result = new AiMatchClient(url -> connection).sentence(settings("deepseek"),
                "你好", "zh-en", Arrays.asList("there","Hello"), new AiMatchClient.Call());
        assertEquals(Arrays.asList("Hello","there"), result.words);
        JSONObject sent = new JSONObject(connection.sent.toString("UTF-8"));
        JSONObject data = new JSONObject(sent.getJSONArray("messages").getJSONObject(1).getString("content"));
        assertEquals("zh-en",data.getString("direction"));
        assertEquals(0,data.getJSONArray("tokens").getJSONObject(0).getInt("id"));
        assertFalse(connection.getInstanceFollowRedirects());
        assertTrue(connection.disconnected);
    }
    @Test public void invalidSentenceAndCancelledRequestNeverReturnAnAnswer() throws Exception {
        Fake invalid = new Fake(200,response("{\"order\":[0,0],\"confidence\":1}"));
        assertTrue(new AiMatchClient(url -> invalid).sentence(settings("compatible"), "你好", "zh-en",
                Arrays.asList("hello"), new AiMatchClient.Call()).words.isEmpty());
        AiMatchClient.Call call = new AiMatchClient.Call();call.cancel();
        assertTrue(new AiMatchClient(url -> {throw new AssertionError("cancelled request opened");})
                .sentence(settings("compatible"), "你好", "zh-en", Arrays.asList("hello"),call).error.contains("取消"));
    }
    @Test public void sentenceDeadlineIsTypedAndDisconnectsWithoutAnswer() throws Exception {
        Fake blocked = new Fake(200, ""); blocked.block = true;
        AiMatchClient.SentenceResult result = new AiMatchClient(url -> blocked).sentence(settings("compatible"),
                "你好", "zh-en", Arrays.asList("hello"), new AiMatchClient.Call());
        assertTrue(result.error, result.timedOut); assertTrue(result.words.isEmpty());
        assertTrue(blocked.disconnected);
    }
    @Test public void socketTimeoutCanRetryOnceAndUseTheSuccessfulResponse() throws Exception {
        Fake slow = new Fake(200, ""); slow.socketTimeout = true;
        Fake ok = new Fake(200, response("{\"order\":[0],\"confidence\":0.99}"));
        Queue<Fake> connections = new ArrayDeque<>(Arrays.asList(slow, ok));
        AiMatchClient client = new AiMatchClient(url -> connections.remove());
        com.wordmatch.assist.sentence.SentenceRetry retry = new com.wordmatch.assist.sentence.SentenceRetry();
        retry.begin();
        AiMatchClient.SentenceResult first = client.sentence(settings("compatible"), "你好", "zh-en",
                Arrays.asList("hello"), new AiMatchClient.Call());
        assertTrue(first.timedOut); assertTrue(slow.disconnected);
        assertTrue(retry.offer(first.timedOut, true)); assertTrue(retry.take(true)); assertTrue(retry.begin());
        AiMatchClient.SentenceResult second = client.sentence(settings("compatible"), "你好", "zh-en",
                Arrays.asList("hello"), new AiMatchClient.Call());
        assertFalse(second.timedOut); assertEquals(Arrays.asList("hello"), second.words);
        assertFalse(retry.offer(second.timedOut, true)); assertTrue(connections.isEmpty());
        assertEquals(1000, slow.getReadTimeout()); assertEquals(1000, ok.getReadTimeout());
    }
    @Test public void cancellationAndHttpErrorTextNeverMasqueradeAsTimeout() throws Exception {
        AiMatchClient.Call cancelled = new AiMatchClient.Call(); cancelled.cancel();
        AiMatchClient.SentenceResult result = new AiMatchClient(url -> {throw new AssertionError("opened cancelled call");})
                .sentence(settings("compatible"), "你好", "zh-en", Arrays.asList("hello"), cancelled);
        assertFalse(result.timedOut); assertTrue(result.error.contains("取消"));
        Fake error = new Fake(401, "{\"error\":{\"message\":\"请求超时 test-secret\"}}");
        result = new AiMatchClient(url -> error).sentence(settings("compatible"), "你好", "zh-en",
                Arrays.asList("hello"), new AiMatchClient.Call());
        assertFalse(result.timedOut); assertFalse(result.error.contains("test-secret"));
        assertTrue(result.words.isEmpty());
    }
    private static final class Fake extends HttpURLConnection {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final byte[] body; final int status;
        volatile boolean disconnected, block, socketTimeout;
        final CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        Fake(int status,String body) throws Exception { super(new URL("https://unit.test/chat/completions")); this.status=status; this.body=body.getBytes(StandardCharsets.UTF_8); }
        public void connect() {}
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected=true; released.countDown(); }
        public OutputStream getOutputStream() { return sent; }
        public int getResponseCode() throws IOException {
            if (socketTimeout) throw new SocketTimeoutException("Read timed out");
            if (block) { entered.countDown(); try { released.await(3,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } throw new IOException("disconnected"); }
            return status;
        }
        public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        public InputStream getErrorStream() { return getInputStream(); }
    }
}
