/*
 * Copyright (c) 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.hadrosaur.videodecodeencodedemo.VideoHelpers

import android.media.*
import android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG
import android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
import android.media.MediaFormat.*
import android.os.Environment
import android.view.Surface
import dev.hadrosaur.videodecodeencodedemo.*
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioOutputBuffer
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.cloneByteBuffer
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.getBufferDurationUs
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.LOG_VIDEO_EVERY_N_FRAMES
import java.io.*
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger


// TODO: there is a memory leak of about 20mb per run. Track it down
/**
 * Encode frames sent into the encoderInputSurface
 *
 * This does not check for an EOS flag. The encode ends when "decodeComplete" is indicated and the
 * number of encoded frames matches the number of decoded frames
 */
class AudioVideoEncoder(val mainActivity: MainActivity, originalRawFileId: Int, val frameLedger: FrameLedger, val audioBufferQueue: ConcurrentLinkedQueue<AudioOutputBuffer>){
    // Video encoding variables
    val videoEncoderInputSurface: Surface
    val videoEncoder: MediaCodec
    val encoderVideoFormat: MediaFormat
    var videoDecodeComplete = false
    var numDecodedVideoFrames = AtomicInteger(0)
    var videoEncodeComplete = false
    var numEncodedVideoFrames = AtomicInteger(0)

    // Video encoder default values, will be changed when video encoder is set up
    var width = 1920
    var height = 1080

    // FPS trackers
    var startTime = 0L
    var endTime = 0L
    var lastPresentationTime = 0L

    // Audio encoding variables
    val audioEncoder: MediaCodec
    val encoderAudioFormat: MediaFormat
    var audioEncodeComplete = false

    // Muxer variables
    var muxer: MediaMuxer? = null
    var encodedFilename = ""
    var isMuxerRunning = false
    var videoTrackIndex: Int = -1
    var audioTrackIndex: Int = -1


    init{
        // Load file from raw directory
        val videoFd = mainActivity.resources.openRawResourceFd(originalRawFileId)
        val videoMimeType = getVideoTrackMimeType(videoFd)
        val videoEncoderCodecInfo = selectEncoder(videoMimeType)
        if (videoEncoderCodecInfo == null) {
            mainActivity.updateLog("WARNING: No valid video encoder codec. Encoded file may be broken.")
        }
        videoEncoder = MediaCodec.createByCodecName(videoEncoderCodecInfo?.getName()!!)
        encoderVideoFormat = getBestVideoEncodingFormat(videoFd)

        // Save encoder width and height for surfaces that use this encoder
        width = encoderVideoFormat.getInteger(KEY_WIDTH)
        height = encoderVideoFormat.getInteger(KEY_HEIGHT)

        val audioMimeType = getAudioTrackMimeType(videoFd)
        val audioEncoderCodecInfo = selectEncoder(audioMimeType)
        if (audioEncoderCodecInfo == null) {
            mainActivity.updateLog("WARNING: No valid audio encoder codec. Audio in encoded file will not work")
        }
        audioEncoder = MediaCodec.createByCodecName(audioEncoderCodecInfo?.getName()!!)
        encoderAudioFormat = getBestAudioEncodingFormat(videoFd)

        videoFd.close()

        // Encoder debug info
        // mainActivity.updateLog("Video encoder: ${videoEncoderCodecInfo?.name}, ${videoFormat}")
        // mainActivity.updateLog("Audio encoder: ${audioEncoderCodecInfo?.name},  ${audioFormat}")

        // Use asynchronous modes with callbacks - encoding logic contained in the callback classes
        // See: https://developer.android.com/reference/android/media/MediaCodec#asynchronous-processing-using-buffers
        videoEncoder.setCallback(VideoEncoderCallback(mainActivity, encoderVideoFormat))
        videoEncoder.configure(encoderVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder.setCallback(AudioEncoderCallback(mainActivity, encoderAudioFormat))
        audioEncoder.configure(encoderAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncodeComplete = false

        // Get the input surface from the encoder, decoded frames from the decoder should be
        // placed here.
        videoEncoderInputSurface = videoEncoder.createInputSurface()

        // Setup Muxer
        val outputFilename = MainActivity.FILE_PREFIX + "-" + generateTimestamp() + ".mp4"
        val outputVideoDir = getAppSpecificVideoStorageDir(mainActivity, MainActivity.FILE_PREFIX)
        val outputVideoFile = File(outputVideoDir, outputFilename)
        encodedFilename = outputVideoFile.name
        muxer = MediaMuxer(outputVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun startEncode() {
        if (videoEncoderInputSurface.isValid) {
            videoEncoder.start()
            audioEncoder.start()
            startTime = System.currentTimeMillis()
            endTime = 0L
        }
    }

    fun finishEncode() {
        if (isMuxerRunning) {
            muxer?.stop()
            isMuxerRunning = false
        }
        mainActivity.encodeFinished()
    }

    fun release() {
        try {
            if (isMuxerRunning) {
                muxer?.stop()
                isMuxerRunning = false
            }
            muxer?.release()
        } catch (e: IllegalStateException) {
            // This will be thrown if the muxer was started but no data sent, ok for this app
            // mainActivity.updateLog("Error stopping VideoEncoder MediaMuxer: ${e.message}.")
        }

        videoEncoder.stop()
        audioEncoder.stop()
        videoEncoder.release()
        audioEncoder.release()
        videoEncoderInputSurface.release();
    }

    fun signalDecodingComplete() {
        videoDecodeComplete = true
    }

    fun signalEncodingComplete() {
        videoEncodeComplete = true
    }

    /**
     * Generate a timestamp to append to saved filenames.
     */
    fun generateTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        return sdf.format(Date())
    }

    /**
     * Get the Movies directory that's inside the app-specific directory on
     * external storage.
     */
    fun getAppSpecificVideoStorageDir(mainActivity: MainActivity, prefix: String): File? {
        val file = File(mainActivity.getExternalFilesDir(
            Environment.DIRECTORY_MOVIES), prefix)

        // Make the directory if it does not exist yet
        if (!file.exists()) {
            if (!file.mkdirs()) {
                mainActivity.updateLog("Error creating encoding directory: ${file.absolutePath}")
            }
        }
        return file
    }

    /**
     * Because video frames are just raw frames coming in from a surface, the encoder needs to
     * manually check if the encode is complete.
     */
    fun checkIfEncodeDone() {
        if ((videoDecodeComplete && audioEncodeComplete && (numEncodedVideoFrames.get() == numDecodedVideoFrames.get()))) {
            endTime = System.currentTimeMillis()
            val totalTime = (endTime - startTime) / 1000.0
            val totalFPS = numEncodedVideoFrames.get() / totalTime
            val timeString = String.format("%.2f", totalTime)
            val FPSString = String.format("%.2f", totalFPS)

            mainActivity.updateLog("Encode done, written to ${encodedFilename}. ${numEncodedVideoFrames.get()} frames in ${timeString}s (${FPSString}fps).")
            signalEncodingComplete()
            finishEncode()
        }
    }

    /**
     * Start the muxer if both the video track and audio track have been set up
     */
    fun startMuxerIfReady() {
        if (videoTrackIndex != -1 && audioTrackIndex != -1) {
            muxer?.start()
            isMuxerRunning = true
        }
    }

    /**
     * The callback functions for Video encoding
     */
    inner class VideoEncoderCallback(val mainActivity: MainActivity, var format: MediaFormat): MediaCodec.Callback() {
        val muxingQueue = LinkedBlockingQueue<MuxingBuffer>()
        val ledgerQueue = LinkedBlockingQueue<LedgerBuffer>()

        // Do not do anything. Incoming frames should
        // be auto-queued into the encoder from the input surface
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            return
        }

        /**
         * A video frame is available from the video encoder.
         *
         * If not a config frame, send it to the muxer. There are 2 possible queues a frame may be
         * added to:
         *  - muxingQueue: if a frame is received from the encoder but the muxer is not yet started
         *  - ledgerQueue: if a frame is received but the decoder has not yet recorder it's proper
         *                 presentation time.
         *
         * Always mux muxing queue first, then ledger queue, then new frames
         */
        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            val outputBuffer = codec.getOutputBuffer(index)

            if (outputBuffer != null) {
                // When a codec config buffer is received, no need to pass it to the muxer
                if (info.flags == BUFFER_FLAG_CODEC_CONFIG) {
                    videoTrackIndex = muxer?.addTrack(format) ?: -1
                    startMuxerIfReady()

                } else {
                    val frameNum = numEncodedVideoFrames.incrementAndGet()

                    // The encode ledger contains the correct presentation time from the decoder,
                    // stored based on the frame number. This assumes frames
                    // hit the decoder output surface in the same order as they exit the encoder.
                    // If this assumption is not true, frames may be encoded out of order.
                    // If the ledger value is not stored by this point in the code,
                    // add the frame to the ledger queue to be muxed when we the correct time
                    if (frameLedger.encodeLedger.containsKey(frameNum)) {
                        info.presentationTimeUs = frameLedger.encodeLedger.get(frameNum)!!
                        // mainActivity.updateLog("Video encoder, got frame number ${frameNum}@${info.presentationTimeUs}, last time was ${lastPresentationTime}.")
                        lastPresentationTime = info.presentationTimeUs

                        // If the muxer hasn't started yet - eg. if the audio stream hasn't been
                        // configured yet - queue the output data for later.
                        if (!isMuxerRunning) {
                            mainActivity.updateLog("Adding buffer to video muxing buffer: ${frameNum}")
                            muxingQueue.add(MuxingBuffer(cloneByteBuffer(outputBuffer), info))
                        } else {
                            // Mux any buffers that were waiting for the muxer to start
                            if (!muxingQueue.isEmpty()) {
                                while (muxingQueue.peek() != null) {
                                    val muxingBuffer = muxingQueue.poll()
                                    mainActivity.updateLog("Muxing buffer out of mux queue: ${muxingBuffer.info.presentationTimeUs}")
                                    muxer?.writeSampleData(
                                        videoTrackIndex,
                                        muxingBuffer.buffer,
                                        muxingBuffer.info
                                    )
                                }
                            }

                            // Check if there are any frames that were not matched with ledger data
                            muxLedgerQueue()

                            // Send the new frame to the muxer
                            muxer?.writeSampleData(videoTrackIndex, outputBuffer, info)

                            // Log current encoding speed
                            if (numEncodedVideoFrames.get() % LOG_VIDEO_EVERY_N_FRAMES == 0) {
                                val currentFPS =
                                    numEncodedVideoFrames.get() / ((System.currentTimeMillis() - startTime) / 1000.0)
                                val FPSString = String.format("%.2f", currentFPS)
                                mainActivity.updateLog("Encoding video stream at ${FPSString}fps, frame $numEncodedVideoFrames.")
                            }
                        } // Is muxer running

                    } else {
                        // No ledger info yet for this buffer, add it to the ledger queue to be processed later
                        mainActivity.updateLog("WARNING: Frame number ${frameNum} not found in ledger, adding to ledgerQueue.")
                        ledgerQueue.add(LedgerBuffer(outputBuffer, info, frameNum))
                    }
               } // If not a config buffer
            } // Is output buffer null
            codec.releaseOutputBuffer(index, false)

            // If encode is finished, there will be no more output buffers received, check manually
            // if video encode is finished
            checkIfEncodeDone()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            mainActivity.updateLog("ERROR: something occurred during video encoding: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            this.format = format
        }

        // Mux any buffers in the ledger queue, if the ledger entry is present and muxer is running
        fun muxLedgerQueue() {
            while (!ledgerQueue.isEmpty()) {
                val ledgerBuffer = ledgerQueue.peek()
                // If there is still no ledger data for this frame, exit and leave it in the queue
                if (frameLedger.encodeLedger.containsKey(ledgerBuffer.frameNum)) {
                    ledgerBuffer.info.presentationTimeUs = frameLedger.encodeLedger.get(ledgerBuffer.frameNum)!!
                    if (isMuxerRunning) {
                        mainActivity.updateLog("Muxing frame ${ledgerBuffer.frameNum} from ledger queue at ${ledgerBuffer.info.presentationTimeUs}")
                        muxer?.writeSampleData(videoTrackIndex, ledgerBuffer.buffer, ledgerBuffer.info)
                        ledgerQueue.poll()
                    }
                } else {
                    break
                }
            } // while
        }
    }

    /**
     * The callback functions for Audio encoding
     */
    inner class AudioEncoderCallback(val mainActivity: MainActivity, var format: MediaFormat): MediaCodec.Callback() {
        val muxingQueue = LinkedBlockingQueue<MuxingBuffer>()

        // Encoder is ready buffers to encode
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Queued buffer might be larger the codec input buffer, do not remove from queue yet
            val audioOutputBuffer = audioBufferQueue.peek()

            // If there is no decoded audio waiting for the encoder, just send an empty buffer
            // Otherwise this will block the encoder
            if (audioOutputBuffer == null) {
                codec.queueInputBuffer(index, 0, 0, 0, 0)
                return
            }

            audioOutputBuffer?.let { // The queued audio buffer

                // Get the available encoder input buffer
                val inputBuffer = codec.getInputBuffer(index)
                if (inputBuffer != null) {
                    // Copy remaining bytes up to the encoder buffers max length
                    val bytesToCopy = Math.min(inputBuffer.capacity(), it.buffer.remaining())

                    // Save old queued buffer limit, set new limit to be the bytes needed
                    val oldQueuedBufferLimit = it.buffer.limit()
                    it.buffer.limit(it.buffer.position() + bytesToCopy)

                    // Copy bytes from queued buffer into encoder buffer, auto advance position
                    inputBuffer.put(it.buffer)

                    // Restore queued buffer's limit
                    it.buffer.limit(oldQueuedBufferLimit)

                     val bufferDurationUs = getBufferDurationUs(bytesToCopy, format)
                     // mainActivity.updateLog("Audio Encode audio buf verification: ${it.presentationTimeUs / 1000}, length: ${bufferDurationUs / 1000}, size: ${it.size}, remaining: ${it.buffer.remaining()}")
                     // mainActivity.updateLog("Audio Encode audio buf verification: size: ${inputBuffer.capacity()}, bytes to copy: ${bytesToCopy}")
                     // mainActivity.updateLog("Audio Encode input buf verification: ${it.presentationTimeUs / 1000}, length: ${bufferDurationUs / 1000}, size: ${bytesToCopy}")

                    // Send to the encoder
                    codec.queueInputBuffer(index, 0, bytesToCopy, it.presentationTimeUs, if(it.isLastBuffer) BUFFER_FLAG_END_OF_STREAM else 0)

                    // If all bytes from the queued buffer have been sent to the encoder, remove from the queue
                    // Otherwise, advance the presentation time for the next chunk of the queued buffer
                    if (!it.buffer.hasRemaining()) {
                        audioBufferQueue.poll()
                    } else {
                        val bufferDurationUs = getBufferDurationUs(bytesToCopy, format)
                        it.presentationTimeUs += bufferDurationUs
                    }
                }
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            val outputBuffer = codec.getOutputBuffer(index)

            if (outputBuffer != null) {
                // When a codec config buffer is received, no need to pass it to the muxer
                if (info.flags == BUFFER_FLAG_CODEC_CONFIG) {
                    audioTrackIndex = muxer?.addTrack(format) ?: -1
                    startMuxerIfReady()

                } else {
                    if(info.flags == BUFFER_FLAG_END_OF_STREAM) {
                        audioEncodeComplete = true
                    }

                    // If the mixer hasn't started yet - eg. if the video stream hasn't been configured
                    // yet - queue the output data for later.
                    if (!isMuxerRunning) {
                        //mainActivity.updateLog("Need to add to mux queue: ${info.presentationTimeUs}")
                        muxingQueue.add(MuxingBuffer(cloneByteBuffer(outputBuffer), info))

                    } else {
                        // Mux any waiting data
                        if (!muxingQueue.isEmpty()) {
                            while(muxingQueue.peek() != null) {
                                val muxingBuffer = muxingQueue.poll()
                                muxer?.writeSampleData(audioTrackIndex, muxingBuffer.buffer, muxingBuffer.info)
                                //mainActivity.updateLog("Muxing audio buffer out of mux queue: ${muxingBuffer.info.presentationTimeUs}")
                            }
                        }

                        // Send the new frame to the muxer
                        muxer?.writeSampleData(audioTrackIndex, outputBuffer, info)
                        //mainActivity.updateLog("Muxing audio buffer: ${info.presentationTimeUs}")
                    }
                }
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            mainActivity.updateLog(("AudioEncoder error: ${e.errorCode} + ${e.diagnosticInfo}"))
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            this.format = format
        }
    }

    inner class MuxingBuffer(val buffer: ByteBuffer, val info: MediaCodec.BufferInfo)
    inner class LedgerBuffer(val buffer: ByteBuffer, val info: MediaCodec.BufferInfo, val frameNum: Int)
}