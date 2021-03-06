package com.wonkytonki.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.wonkytonki.common.AudioFrame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Timer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;


public class MainActivity extends ActionBarActivity {
    private static final int NOTIFICATION_ID = 001;
    private static final String LOG_TAG = "WONKY";

    private static final BlockingQueue<byte[]> audioBytes = new ArrayBlockingQueue<byte[]>(1000);

    private static final int RECORDER_SAMPLERATE = 8000;
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BufferElements2Rec = 1024; // want to play 2048 (2K) since 2 bytes we use only 1024
    private static final int BytesPerElement = 2; // 2 bytes in 16bit format
    private static final int TCP_PORT = 54555;
    private static final int UDP_PORT = 54777;
    private static final int AUDIO_THRESHOLD = 14000;
    private static final int BUFFERT_SIZE = 1024 * 1024;

    private static final float BUTTON_ALPHA_OFF = 0.4f;
    private static final float BUTTON_ALPHA_ON = 1.0f;

    //private static final String SERVER_ADDRESS = "192.168.0.134";
    private static final String SERVER_ADDRESS = "78.73.132.182";

    private AudioRecord recorder = null;
    private Thread recordingThread = null;
    private boolean isRecording = false;

    private Button mButtonTalk;
    private TextView mTextViewBottom;
    private TextView mTextViewTop;

    private Button mButtonForceReconnect;
    private Client mClient;

    private AudioFrame mAudioFrame;
    private boolean mIsConnected = false;
    private Thread mSendingThread;
    private boolean mDoReconnect = true;

    private static void disableButton(Button b) {
        b.setEnabled(false);
        b.setAlpha(BUTTON_ALPHA_OFF);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mButtonTalk = (Button) findViewById(R.id.main_btn_talk);
        mTextViewBottom = (TextView) findViewById(R.id.main_txt_bottom);
        mTextViewTop = (TextView) findViewById(R.id.main_txt_top);
        mButtonForceReconnect = (Button) findViewById(R.id.main_btn_reconnect);

        mClient = new Client(BUFFERT_SIZE, BUFFERT_SIZE);

        com.esotericsoftware.minlog.Log.setLogger(new AndroidLogger());
        com.esotericsoftware.minlog.Log.set(com.esotericsoftware.minlog.Log.LEVEL_WARN);

        Kryo k = mClient.getKryo();
        k.setRegistrationRequired(false);

        setupActionBar();
        setTitle(R.string.title);
        setup();
    }

    public void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
        actionBar.setDisplayShowTitleEnabled(true);
    }

    @Override
    protected void onDestroy() {
        killApp();
        super.onDestroy();
    }

    private void killApp() {
        stopRecording();
        mDoReconnect = false;
        if (mSendingThread != null) {
            mSendingThread.interrupt();
            mSendingThread = null;
        }
        if (mClient != null) {
            mClient.stop();
        }
        hideNotification();
    }

    private void hideNotification() {
        NotificationManager manager = NotificationManager.class.cast(getSystemService(NOTIFICATION_SERVICE));
        manager.cancel(NOTIFICATION_ID);
    }

    private void showNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,intent, 0);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this)
                        .setSmallIcon(R.drawable.ic_running)
                        .setContentTitle(getString(R.string.notification_running_title))
                        .setContentText(getString(R.string.notification_running_text))
                        .setContentIntent(pendingIntent)
                        .setOngoing(true);
        // Sets an ID for the notification
        // Gets an instance of the NotificationManager service
        NotificationManager manager = NotificationManager.class.cast(getSystemService(NOTIFICATION_SERVICE));
        // Builds the notification and issues it.
        final Notification notification = builder.build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    private void setup() {
        int bufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING);
        final AudioTrack audioPlayer = new AudioTrack(AudioManager.STREAM_MUSIC, 8000, AudioFormat.CHANNEL_CONFIGURATION_MONO,
                AudioFormat.ENCODING_PCM_16BIT, BufferElements2Rec, AudioTrack.MODE_STREAM);

        if (audioPlayer.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            audioPlayer.play();
        }

        final Handler h = new Handler();

        mAudioFrame = new AudioFrame();
        if(!mClient.isConnected()) {
            mClient.addListener(new Listener() {
                @Override
                public void connected(Connection connection) {
                    super.connected(connection);
                    onConnectedToServer();
                }

                @Override
                public void disconnected(Connection connection) {
                    super.disconnected(connection);
                    onDisconnectedFromServer();
                    if(mDoReconnect) {
                        connectToServer();
                    }
                }

                public void received(Connection connection, Object object) {
                    super.received(connection, object);
                    if (object instanceof AudioFrame) {
                        mAudioFrame = (AudioFrame) object;
                        final short[] sData = new short[mAudioFrame.bytes.length / 2];
                        ByteBuffer.wrap(mAudioFrame.bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sData);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (AudioRecord.ERROR_INVALID_OPERATION != sData.length) {
                                    audioPlayer.write(sData, 0, sData.length);
                                    audioPlayer.flush();
                                } else {
                                    Log.e(LOG_TAG, "Error: AudioRecord.ERROR_INVALID_OPERATION != sData.length");
                                }
                                setTitle(String.format("%d users connected", mAudioFrame.users));
                            }
                        });
                    }
                }
            });

            connectToServer();

            if(mSendingThread == null) {
                mSendingThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        connectToServer();
                        byte[] bytes;
                        try {
                            while ((bytes = MainActivity.audioBytes.take()) != null && !Thread.currentThread().isInterrupted()) {
                                AudioFrame frame = new AudioFrame();
                                frame.bytes = bytes;
                                frame.time = System.currentTimeMillis();
                                frame.users = -1;
                                mClient.sendTCP(frame);
                            }
                        } catch (InterruptedException e) {
                            Log.d(LOG_TAG, "mSendingThread was interrupted");
                        }
                    }
                });
                mSendingThread.start();
            }
        }

        mButtonTalk.setEnabled(false);
        mButtonTalk.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN:
                        mButtonTalk.setPressed(true);
                        mTextViewBottom.setText(R.string.btn_text_release);
                        isRecording = true;
                        startRecording();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        mButtonTalk.setPressed(false);
                        mTextViewBottom.setText(R.string.btn_text_push);
                        isRecording = false;
                        stopRecording();
                        break;
                    default:
                        break;
                }
                return true;
            }
        });

        mButtonForceReconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectToServer();
            }
        });
    }

    private void onConnectedToServer() {
        mIsConnected = true;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextViewBottom.setText(R.string.con_connected);
                enableButton(mButtonTalk);
                disableButton(mButtonForceReconnect);
                showNotification();
            }
        });
    }

    private void enableButton(Button b) {
        b.setEnabled(true);
        b.setAlpha(BUTTON_ALPHA_ON);
    }

    private void onDisconnectedFromServer() {
        mIsConnected = false;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                hideNotification();
            }
        });
    }

    private boolean connectToServer() {
        onConnectingToServer();
        runOnAsyncThread(new Runnable() {
            @Override
            public void run() {
                try {
                    mClient.start();
                    mClient.connect(3000, SERVER_ADDRESS, TCP_PORT, UDP_PORT);
                } catch (IOException e) {
                    onConnectingFailed();
                    Log.d(LOG_TAG, "connectToServer(): failed", e);
                }
            }
        });
        return true;

    }

    private void onConnectingFailed() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextViewBottom.setText(R.string.con_failed_try_again);
                disableButton(mButtonTalk);
                enableButton(mButtonForceReconnect);
            }
        });
    }

    private void onConnectingToServer() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextViewBottom.setText(R.string.con_connecting);
                disableButton(mButtonTalk);
                disableButton(mButtonForceReconnect);
                hideNotification();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu items for use in the action bar
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_activity_actions, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle presses on the action bar items
        switch (item.getItemId()) {
            case R.id.action_exit:
                killApp();
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void startRecording() {
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                RECORDER_SAMPLERATE, RECORDER_CHANNELS,
                RECORDER_AUDIO_ENCODING, BufferElements2Rec * BytesPerElement);
        recorder.stop();
        recorder.startRecording();
        isRecording = true;
        recordingThread = new Thread(new Runnable() {
            public void run() {
                writeAudioDataToFile();
            }
        }, "AudioRecorder Thread");
        recordingThread.start();
    }

    private byte[] short2byte(short[] sData) {
        byte[] bytes2 = new byte[sData.length * 2];
        ByteBuffer.wrap(bytes2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(sData);
        return bytes2;
    }

    private void writeAudioDataToFile() {
        // Write the output audio in byte
        short sData[] = new short[BufferElements2Rec];
        while (isRecording) {
            // gets the voice output from microphone to byte format
            int readBytes = recorder.read(sData, 0, BufferElements2Rec);
            if (AudioRecord.ERROR_INVALID_OPERATION != readBytes) {
                byte bData[] = short2byte(sData);
                if (isSilentAudioData(bData)) {
                    try {
                        audioBytes.put(bData);
                    } catch (InterruptedException e) {
                        Log.d(LOG_TAG, "Interrupted while adding to audio queue", e);
                    }
                }
            }
        }
    }

    private boolean isSilentAudioData(byte[] arr) {
        long sum = 0L;
        for (byte b : arr) {
            sum += Math.abs(b);
        }
        return sum > AUDIO_THRESHOLD;
    }

    private void stopRecording() {
        // stops the recording activity
        if (null != recorder) {
            recorder.stop();
            isRecording = false;
            recorder.release();
            recorder = null;
            recordingThread.interrupt();
            recordingThread = null;
        }
    }

    public void runOnAsyncThread(final Runnable e) {
        final AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                e.run();
                return null;
            }
        };
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class AndroidLogger extends com.esotericsoftware.minlog.Log.Logger {
        @Override
        public void log(int lvl, String tag, String msg, Throwable ex) {
            if (ex == null) {
                Log.d(String.valueOf(tag).toUpperCase(), msg);
            } else {
                Log.e(String.valueOf(tag).toUpperCase(), msg, ex);
            }
        }
    }
}