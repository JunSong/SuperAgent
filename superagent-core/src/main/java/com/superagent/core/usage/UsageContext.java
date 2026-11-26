package com.superagent.core.usage;

/**
 * Who a model run is for, so its tokens land on the right person. {@code user} pays: the person whose work it was
 * (the task's creator, whoever sent the chat message, the SuperAgent's owner). The rest say what it was for; any may
 * be null.
 *
 * <p>{@code sessionId} keeps its chat/task session semantics (e.g. {@code latestChatTurn}). {@code runId} is the
 * managed-gateway identity used only for settlement shadowing (#34) — never overwrite sessionId with it.
 *
 * @param source one of the {@code SOURCE_*} constants
 * @param runId  managed model-run id when the CLI went through the company gateway; null otherwise
 */
public record UsageContext(String user, String source, String agentId, String taskId, String sessionId, String cli,
                           String runId) {

    public static final String SOURCE_TASK = "task";
    public static final String SOURCE_CHAT = "chat";
    public static final String SOURCE_SUPERAGENT = "superagent";
    public static final String SOURCE_BACKGROUND = "superagent_background";
    public static final String SOURCE_MODEL_CHOICE = "model_choice";
    public static final String SOURCE_MEMORY = "memory";
    public static final String SOURCE_FILE_TAGS = "file_tags";
    public static final String SOURCE_TASK_TAGS = "task_tags";
    public static final String SOURCE_TRANSCRIBE = "transcribe";
    public static final String SOURCE_SESSION_HANDOFF = "session_handoff";
    /** Model-gateway settlement projection (#34); used when report mode is GATEWAY_PRIMARY. */
    public static final String SOURCE_GATEWAY = "gateway";

    /** Callers without a managed run id. */
    public UsageContext(String user, String source, String agentId, String taskId, String sessionId, String cli) {
        this(user, source, agentId, taskId, sessionId, cli, null);
    }
}
