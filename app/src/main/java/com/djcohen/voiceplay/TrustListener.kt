package com.djcohen.voiceplay

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Android treats an app with notification access as "trusted for media control", and
 * Metrolist 14+ only lets trusted apps search its library and start songs. We never read any notification.
 */
class TrustListener : NotificationListenerService()
