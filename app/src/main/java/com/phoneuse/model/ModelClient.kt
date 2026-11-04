package com.phoneuse.model

interface ModelClient {
    /** [onTextDelta] may be invoked on a background (IO) thread. */
    suspend fun next(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit = {},
    ): ModelTurn

    /**
     * Like [next], also reporting each hosted web search as it runs, once per search. [onSearch] may be invoked
     * on a background thread. Clients without hosted tools never call it.
     */
    suspend fun next(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
        onSearch: (WebSearchCall) -> Unit,
    ): ModelTurn = next(instructions, input, tools, onTextDelta)
}
