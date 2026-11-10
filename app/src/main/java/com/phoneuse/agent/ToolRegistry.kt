package com.phoneuse.agent

/**
 * The agent's tool modules, in the order their tools appear in the model's tool list. That list is part of the
 * prompt and its cache (pinned by ToolSpecsPinTest), so reordering it costs every user a cold cache: add a new
 * module just above the connectors, which change per run anyway. A module that needs the phone itself takes
 * [ToolDeps.context], which is null in unit tests, e.g. `d.context?.let { DeviceInfoModule(BatteryProvider.of(it)) }`.
 */
internal object ToolRegistry {
    fun modules(d: ToolDeps): List<ToolModule> {
        val phone = PhoneModule(
            d.phone, d.device, d.confirming, d.runTainted, d.historyTainted,
            asks = d.gate, userAskedCall = d.userAskedCall, userAskedAlarm = d.userAskedAlarm, userAskedTimer = d.userAskedTimer,
        )
        return listOfNotNull(
            d.screen,
            FileModule(d.files, d.confirming),
            UserModule(d.gate),
            MemoryModule(d.memory, d.gate) { d.historyTainted || d.runTainted() },
            phone,
            RoutineModule(d.routines),
            // show_card measures a places card from this run's get_location fix.
            ReplyModule { phone.lastLocation },
            MessagingModule(d.messaging, d.gate, d.userFixedSend, d.userAskedDelete),
            PlanModule(),
            ControlsModule(d.phone?.controls),
            PeopleModule(d.phone?.callLog, d.phone?.contactAdder, d.gate, d.userGaveContact),
            ConnectorModule(d.connectors, d.confirming),
        )
    }
}
