package de.reibisch.hnaudio.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Maps the transport controls onto stories. Notification, lock screen and Bluetooth buttons
 * all go through the media session to this player, so they behave the same:
 * Next = next story, Previous = read full article (or restart the paragraph during one).
 */
class HnPlayer(player: Player, private val queue: StoryQueue) : ForwardingPlayer(player) {

    override fun play() {
        if (!queue.isStarted || queue.canRestart) queue.start()
        super.play()
    }

    override fun seekToNext() = queue.next()
    override fun seekToNextMediaItem() = queue.next()
    override fun seekToPrevious() = queue.previous()
    override fun seekToPreviousMediaItem() = queue.previous()

    // Keep Next/Previous enabled even at the end of the playlist, e.g. while the next story
    // is still being prepared, so headsets and the notification don't drop the buttons.
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*STORY_COMMANDS).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command in STORY_COMMANDS || super.isCommandAvailable(command)

    private companion object {
        val STORY_COMMANDS = intArrayOf(
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}
