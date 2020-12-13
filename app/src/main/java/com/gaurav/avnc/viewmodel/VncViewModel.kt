/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.viewmodel

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.ui.vnc.FrameState
import com.gaurav.avnc.ui.vnc.FrameView
import com.gaurav.avnc.vnc.Messenger
import com.gaurav.avnc.vnc.UserCredential
import com.gaurav.avnc.vnc.VncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue

/**
 * ViewModel for VncActivity
 *
 * Connection
 * ==========
 *
 * At construction, we instantiate a [VncClient] referenced by [client]. Then
 * activity starts the connection by calling [connect] which starts a coroutine to
 * handle connection setup.
 *
 * After successful connection, we continue to operate normally until remote
 * server closes the connection OR [disconnect] is called. Once disconnected, we
 * wait for the activity to finish and then cleanup any acquired resources.
 *
 * Currently, lifecycle of [client] is tied to this view model. So one [VncViewModel]
 * manages only one [VncClient].
 *
 *
 * Threading
 * =========
 *
 * Receiver thread :- This thread is started (as a coroutine) in [launchConnection].
 * It handles the protocol initialization and after that processes incoming messages.
 * Most of the callbacks of [VncClient.Observer] are invoked on this thread. In most
 * cases it is stopped when activity is finished and this view model is cleaned up.
 *
 * Sender thread :- This thread is created (as an executor) by [messenger]. It is
 * used to send messages to remote server. We use this dedicated thread instead
 * of coroutines to preserve the order of sent messages. It is stopped after [client]
 * is disconnected.
 *
 * UI thread :- Main thread of the app. Used for updating UI and controlling other
 * Threads. This is where [frameState] is updated.
 *
 * Renderer thread :- This is managed by [FrameView] and used for rendering frame
 * via OpenGL ES. [frameState] is read from this thread to decide how/where frame
 * should be drawn.
 */
class VncViewModel(app: Application) : BaseViewModel(app), VncClient.Observer {

    val client = VncClient(this)

    /**
     * Client state is exposed as live data here to allow dynamic data binding
     * from layouts.
     */
    val clientState = MutableLiveData(client.state)

    /**
     * [ServerProfile] used for current connection.
     */
    var profile = ServerProfile()

    /**
     * Fired when [VncClient] has asked for credential. It is used to
     * show Credentials dialog to user.
     *
     * Value of this event is true if `username` is also required.
     * It is false if only password is required.
     */
    val credentialRequiredEvent = LiveEvent<Boolean>()

    /**
     * Used for credential transfer between credential dialog (producer)
     * and the thread doing VNC initialization (consumer).
     *
     * After firing [credentialRequiredEvent] event, consumer will block
     * waiting for credentials. After producer has received credentials from
     * user, it will put them in this queue, allowing consumer to continue.
     */
    val credentialQueue = LinkedBlockingQueue<UserCredential>()

    /**
     * List of known credentials. Used for providing suggestion when
     * new credentials are required.
     */
    val knownCredentials by lazy { serverProfileDao.getCredentials() }

    /**
     * Holds a weak reference to [FrameView] instance.
     *
     * This is used to tell [FrameView] to re-render its content when VncClient's
     * framebuffer is updated. Instead of of using LiveData/LiveEvent, we keep a
     * weak reference because:
     *
     *      1. It avoids a context-switch to UI thread. Rendering request to
     *         a GlSurfaceView can be sent from any thread.
     *
     *      2. We don't have to invoke the whole ViewModel machinery just for
     *         a single call to FrameView.
     */
    var frameViewRef = WeakReference<FrameView>(null)

    /**
     * Holds information about scaling, translation etc.
     */
    val frameState = FrameState(pref)

    /**
     * Used for sending events to remote server.
     */
    val messenger = Messenger(client)


    /**************************************************************************
     * Connection management
     **************************************************************************/

    /**
     * Because [connect] can be called multiple times due to activity restarts,
     * initialization inside it is performed only once.
     */
    private var initialized = false

    /**
     * Initialize VNC connection using given profile.
     */
    fun connect(profile: ServerProfile) {
        if (initialized)
            return

        initialized = true
        this.profile = profile

        launchConnection()
    }

    private fun launchConnection() {
        viewModelScope.launch(Dispatchers.IO) {

            try {

                if (client.connect(profile.address, profile.port)) {
                    while (isActive && client.processServerMessage()) {
                        //Message Loop
                    }
                }

                //Wait until cancellation - which will happen when activity
                //is finished and viewmodel is cleaned up.
                while (isActive) {
                    delay(3600 * 1000)
                }
            } finally {
                messenger.cleanup()
                client.cleanup()
            }
        }
    }

    /**
     * Sends current clipboard text to remote server.
     */
    fun sendClipboardText() {
        val text = clipboard.text

        if (text != null && text.isNotBlank())
            messenger.sendClipboardText(text.toString())
    }

    /**
     * Disconnect VNC client.
     */
    fun disconnect() = client.disconnect()

    /**
     * Called when activity is finished.
     */
    override fun onCleared() {
        super.onCleared()

        //Put something in credential queue (just in case background thread is
        //stuck waiting for credentials)
        credentialQueue.offer(UserCredential())
    }


    /**************************************************************************
     * Frame management
     **************************************************************************/

    fun updateZoom(scaleFactor: Float, fx: Float, fy: Float) {
        val appliedScaleFactor = frameState.updateZoom(scaleFactor)

        //Calculate how much the focus would shift after scaling
        val dfx = (fx - frameState.frameX) * (appliedScaleFactor - 1)
        val dfy = (fy - frameState.frameY) * (appliedScaleFactor - 1)

        //Translate in opposite direction to keep focus fixed
        frameState.pan(-dfx, -dfy)

        frameViewRef.get()?.requestRender()
    }

    fun panFrame(deltaX: Float, deltaY: Float) {
        frameState.pan(deltaX, deltaY)
        frameViewRef.get()?.requestRender()
    }

    fun moveFrameTo(x: Float, y: Float) {
        frameState.moveTo(x, y)
        frameViewRef.get()?.requestRender()
    }


    /**************************************************************************
     * [VncClient.Observer] Implementation
     **************************************************************************/

    /**
     * Called when remote server has asked for password.
     */
    override fun onPasswordRequired(): String {
        if (!profile.password.isBlank())
            return profile.password

        return obtainCredential(false).password
    }

    /**
     * Called when remote server has asked for both username & password.
     */
    override fun onCredentialRequired(): UserCredential {
        if (!profile.username.isBlank() && !profile.password.isBlank())
            return UserCredential(profile.username, profile.password)

        return obtainCredential(true)
    }

    /**
     * Used for obtaining credentials from user.
     * This is a blocking operation and will wait until credential dialog is finished.
     */
    private fun obtainCredential(usernameRequired: Boolean): UserCredential {
        credentialQueue.clear()
        credentialRequiredEvent.post(usernameRequired)
        return credentialQueue.take()   //Blocking call
    }

    override fun onFramebufferUpdated() {
        frameViewRef.get()?.requestRender()
    }

    override fun onGotXCutText(text: String) {
        setClipboardText(text)
    }

    override fun onClientStateChanged(newState: VncClient.State) {
        clientState.postValue(newState)

        if (newState == VncClient.State.Connected) {
            viewModelScope.launch(Dispatchers.Main) {
                sendClipboardText() //Initial sync
            }

            //Save any changes to profile. Right now this is used to "remember" credentials.
            if (profile.ID != 0L) async {
                serverProfileDao.update(profile)
            }
        }
    }

    override fun onFramebufferSizeChanged(width: Int, height: Int) {
        frameState.setFramebufferSize(width.toFloat(), height.toFloat())
    }
}