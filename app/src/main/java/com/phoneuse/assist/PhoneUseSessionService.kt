package com.phoneuse.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Creates one [PhoneUseSession] per assistant invocation. */
class PhoneUseSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = PhoneUseSession(this)
}
