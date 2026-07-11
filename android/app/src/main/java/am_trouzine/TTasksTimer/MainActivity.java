package am_trouzine.TTasksTimer; 

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import android.util.Base64;
import android.widget.Toast;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;

import android.database.Cursor;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.os.Looper;
import android.media.MediaPlayer;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int WRITE_REQUEST_CODE = 43;
    private static final int FILE_CHOOSER_REQUEST_CODE = 44;
    
    private String pendingData = ""; 
    private String pendingAudioPath = ""; 
    private String lastPickedFileDataUrl = "";

    private final Handler ttsHandler = new Handler(Looper.getMainLooper());

    private WebView mWebView = null; 
    private TextToSpeech mTTS = null;
    private MediaPlayer mPlayer = null;

    private MediaPlayer mBgPlayer = null;
    private MediaPlayer mSoundPlayer = null;

    private boolean isDucked = false;
    private int duckCount = 0;
    
    public void alertFromJava(String message) {
        if (mWebView != null && message != null) {
            String cleanMessage = message.replace("'", "\\'").replace("\n", "\\n");
            ttsHandler.post(new JavaScriptAlertRunnable(mWebView, cleanMessage));
        }
    }

    public void toastFromJava(final String message) {
        if (message != null) {
            ttsHandler.post(new ToastRunnable(this, message));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        clearCacheFolder();
        
        mTTS = new TextToSpeech(this, this);
        
        mWebView = new WebView(this);
        WebSettings settings = mWebView.getSettings();
        
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true); 
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true); 
        settings.setBlockNetworkLoads(true);           
        settings.setMediaPlaybackRequiresUserGesture(false);
        /*if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.KITKAT) {
        	settings.setTextZoom(100); // Forces standard scale regardless of system accessibility sizes
        }*/
        
        //mWebView.setWebViewClient(new WebViewClient());
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // If it's a web link, pass it to the system browser
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        // Crucial fallback for legacy devices to prevent crashing if no browser is found
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); 
                        view.getContext().startActivity(intent);
                        return true; // Tells the WebView: "Java handled this, don't load it here!"
                    } catch (Exception e) {
                        toastFromJava("No browser found to open link.");
                        return true;
                    }
                }
                
                // Return false for local assets (like file:///...) so they load inside the WebView naturally
                return false; 
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Keep your existing onPageFinished code here if you have any
            }
        });
        mWebView.setWebChromeClient(new WebChromeClient());
        /*
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                // Format a clear error string with the log message, line number, and file source
                String logDetail = "JS Console [" + consoleMessage.messageLevel() + "]\n"
                        + consoleMessage.message() + "\n"
                        + "(Line: " + consoleMessage.lineNumber() + " in " + consoleMessage.sourceId() + ")";
                
                // Show it on screen as a short Toast notification
                Toast.makeText(MainActivity.this, logDetail, Toast.LENGTH_LONG).show();
                
                return true; // Tells the system we intercepted the log successfully
            }
        });
        */
        mWebView.addJavascriptInterface(new FileHandler(this), "Android");
        mWebView.loadUrl("file:///android_asset/index.html");
        setContentView(mWebView);
    }
    
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && mTTS != null) {
            // Register the listener safely
            mTTS.setOnUtteranceProgressListener(mUtteranceListener);
        }
    }

    // Defining it explicitly avoids R8/ProGuard structural compilation bugs on legacy systems
    private final UtteranceProgressListener mUtteranceListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            // Speech started
        }

        @Override
        public void onDone(final String utteranceId) {
            ttsHandler.post(new Runnable() {
                @Override
                public void run() {
                    duck(false); // Unduck when done
                }
            });
        }

        @Override
        public void onError(String utteranceId) {
            ttsHandler.post(new Runnable() {
                @Override
                public void run() {
                    duck(false); // Safety unduck on error
                }
            });
        }
    };

    /*
    public void speak(String text, String lang) {
        if (mTTS != null) {
            Locale locale = new Locale(lang);
            mTTS.setLanguage(locale);
            mTTS.speak(text, TextToSpeech.QUEUE_ADD, null);
        }
    }
    */
    public void speak(String text, String lang) {
        if (mTTS != null) {
            // 1. Duck the audio before speaking
            duck(true);

            Locale locale = new Locale(lang);
            mTTS.setLanguage(locale);
            
            // 2. We must pass a unique utterance ID bundle for the listener to trigger
            java.util.HashMap<String, String> params = new java.util.HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "tts_" + System.currentTimeMillis());
            
            mTTS.speak(text, TextToSpeech.QUEUE_ADD, params);
        }
    }

    public void saveTts(String text, String filename) {
        if (mTTS != null) {
            java.io.File tempFile = new java.io.File(getCacheDir(), filename + ".wav");
            pendingAudioPath = tempFile.getAbsolutePath();
            
            java.util.HashMap<String, String> myHashRender = new java.util.HashMap<String, String>();
            myHashRender.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "id_" + filename);
            
            mTTS.synthesizeToFile(text, myHashRender, tempFile.getAbsolutePath());
            
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/wav");
            intent.putExtra(Intent.EXTRA_TITLE, filename + ".wav");
            try {
                startActivityForResult(intent, 45); 
            } catch (Exception e) {}
        }
    }

//== audio
    public void playBgAudioFromBase64(String base64Data) {
	 try {
	  if (mBgPlayer != null) {
	   mBgPlayer.stop();
	   mBgPlayer.release();
	   mBgPlayer = null;
	  }
	  
	  String cleanBase64 = base64Data;
	  if (base64Data.contains(",")) {
	   cleanBase64 = base64Data.split(",")[1];
	  }
	  
	  byte[] audioBytes = Base64.decode(cleanBase64, Base64.DEFAULT);
	  
	  java.io.File tempAudioFile = new java.io.File(getCacheDir(), "native_inline_bg.wav");
	  java.io.FileOutputStream fos = new java.io.FileOutputStream(tempAudioFile);
	  fos.write(audioBytes);
	  fos.close();
	  
	  mBgPlayer = new MediaPlayer();
	  mBgPlayer.setDataSource(tempAudioFile.getAbsolutePath());
	  mBgPlayer.setLooping(true);
	  mBgPlayer.prepare();
	  float vol = isDucked ? 0.2f : 1.0f;
	  mBgPlayer.setVolume(vol, vol);
	  mBgPlayer.start();
	  
	 } catch (Exception e) {
        toastFromJava("Audio Playback Error: " + e.getMessage());
    }
    }
	
	public void stopBgAudio() {
        try {
            if (mBgPlayer != null) {
                mBgPlayer.stop();
                mBgPlayer.release();
                mBgPlayer = null;
            }
        } catch (Exception e) {
        toastFromJava("Stop Error: " + e.getMessage());
    }
    }

    public void playAudioFromBase64(String base64Data) {
	 try {
	  if (mSoundPlayer != null) {
	   mSoundPlayer.stop();
	   mSoundPlayer.release();
	   mSoundPlayer = null;
	  }
	  
	  String cleanBase64 = base64Data;
	  if (base64Data.contains(",")) {
	   cleanBase64 = base64Data.split(",")[1];
	  }
	  
	  byte[] audioBytes = Base64.decode(cleanBase64, Base64.DEFAULT);
	  
	  java.io.File tempAudioFile = new java.io.File(getCacheDir(), "native_inline_audio.wav");
	  java.io.FileOutputStream fos = new java.io.FileOutputStream(tempAudioFile);
	  fos.write(audioBytes);
	  fos.close();
	  
	  mSoundPlayer = new MediaPlayer();
	  mSoundPlayer.setDataSource(tempAudioFile.getAbsolutePath());
	  //mSoundPlayer.setOnCompletionListener(this);
	  mSoundPlayer.prepare();
	  float vol = isDucked ? 0.2f : 1.0f;
	  mSoundPlayer.setVolume(vol, vol);
	  if (mBgPlayer != null) {
	   mBgPlayer.setVolume(0.1f, 0.1f);
	  }
	  mSoundPlayer.start();
	mSoundPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
          @Override
          public void onCompletion(MediaPlayer mp) {
              // Unduck audio when the short sound finishes playing
              if (mBgPlayer != null) {
              	float vol = isDucked ? 0.2f : 1.0f;
	   mBgPlayer.setVolume(vol, vol);
	  }
          }
      });
	
	  
	  
	 } catch (Exception e) {
        toastFromJava("Audio Playback Error: " + e.getMessage());
    }
    }
	
	public void stopAudio() {
        try {
            if (mSoundPlayer != null) {
                mSoundPlayer.stop();
                mSoundPlayer.release();
                mSoundPlayer = null;
            }
        } catch (Exception e) {
        toastFromJava("Stop Error: " + e.getMessage());
    }
    }

    public void duck(boolean on) {
	 if (on) {
	  duckCount++;
	  isDucked = true;
	  if (mSoundPlayer != null) {
	   mSoundPlayer.setVolume(0.2f, 0.2f);
	  } else {
	   if (mBgPlayer != null) mBgPlayer.setVolume(0.2f, 0.2f);
	  }
	 } else {
	  if (duckCount > 0) duckCount--;
	  if (duckCount == 0) {
	   isDucked = false;
	   if (mSoundPlayer != null) {
		mSoundPlayer.setVolume(1.0f, 1.0f);
	   } else {
		if (mBgPlayer != null) mBgPlayer.setVolume(1.0f, 1.0f);
	   }
	  }
	 }
	}
	
	public void playNativeTone(final int frequency, final double durationSeconds) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int sampleRate = 8000; // 8kHz is plenty for basic beeps/ticks and saves memory
                int numSamples = (int) (durationSeconds * sampleRate);
                double[] sample = new double[numSamples];
                byte[] generatedSnd = new byte[2 * numSamples];

                // Fill the array with a pure sine wave formula
                for (int i = 0; i < numSamples; ++i) {
                    sample[i] = Math.sin(2 * Math.PI * i / (sampleRate / frequency));
                }

                // Convert the sine wave to 16-bit PCM bytes
                int idx = 0;
                for (final double dVal : sample) {
                    // Scale to maximum amplitude for 16-bit audio
                    final short val = (short) ((dVal * 32767));
                    // Byte 1: Low byte
                    generatedSnd[idx++] = (byte) (val & 0x00ff);
                    // Byte 2: High byte
                    generatedSnd[idx++] = (byte) ((val &  0xff00) >>> 8);
                }

                try {
                    // Create the raw audio track player
                    android.media.AudioTrack audioTrack = new android.media.AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC,
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        generatedSnd.length,
                        android.media.AudioTrack.MODE_STATIC
                    );

                    // Write the PCM data to the hardware and play it
                    audioTrack.write(generatedSnd, 0, generatedSnd.length);
                    
                    // Apply current ducking state to the tone if necessary
                    //float vol = isDucked ? 0.2f : 1.0f;
                    //audioTrack.setStereoVolume(vol, vol);
                    
                    audioTrack.play();

                    // Clean up the track memory once the duration has passed
                    Thread.sleep((long) (durationSeconds * 1000));
                    audioTrack.stop();
                    audioTrack.release();

                } catch (Exception e) {
        toastFromJava("Tone Generation Error: " + e.getMessage());
    }
            }
        }).start();
    }

    public void pauseAllAudios() {
	 if (mBgPlayer != null && mBgPlayer.isPlaying()) mBgPlayer.pause();
	 if (mSoundPlayer != null && mSoundPlayer.isPlaying()) mSoundPlayer.pause();
    }
	
	public void resumeAllAudios() {
		duckCount = 0;
	 isDucked = false;
	 float vol = isDucked ? 0.2f : 1.0f; 
	 if (mBgPlayer != null){
		 mBgPlayer.setVolume(vol, vol);
		 mBgPlayer.start();
	 }
	 if (mSoundPlayer != null){
		 if (mBgPlayer != null) mBgPlayer.setVolume(0.1f, 0.1f);
		 mSoundPlayer.setVolume(vol, vol);
		 mSoundPlayer.start();
	 }
    }

	public void stopAllAudios() {
	 duckCount = 0;
	 isDucked = false;
	 stopBgAudio();
	 stopAudio();
	clearCacheFolder();
    }
//== audio end

    public void playNativeAudioFromBase64(String base64Data) {
        try {
            if (mPlayer != null) {
                stopNativeAudioFromWeb(); // Cleanly resets previous tracks safely
            }

            String cleanBase64 = base64Data;
            if (base64Data.contains(",")) {
                cleanBase64 = base64Data.split(",")[1];
            }

            byte[] audioBytes = Base64.decode(cleanBase64, Base64.DEFAULT);

            java.io.File tempAudioFile = new java.io.File(getCacheDir(), "native_inline_speak.wav");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempAudioFile);
            fos.write(audioBytes);
            fos.close();

            mPlayer = new MediaPlayer();
            mPlayer.setDataSource(tempAudioFile.getAbsolutePath());
            mPlayer.prepare();

            // 1. Success! Trigger onplay via web thread
            ttsHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mWebView != null) {
                        mWebView.loadUrl("javascript:if(window.AudioControl){window.AudioControl.onplay();}");
                    }
                }
            });

            // 2. Track Ends! Trigger onpause via web thread
            mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    ttsHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mWebView != null) {
                                mWebView.loadUrl("javascript:if(window.AudioControl){window.AudioControl.onpause();}");
                            }
                        }
                    });
                }
            });

            mPlayer.start();

        } catch (Exception e) {
            toastFromJava("Audio Playback Error: " + e.getMessage());
            ttsHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mWebView != null) {
                        mWebView.loadUrl("javascript:if(window.AudioControl){window.AudioControl.onpause();}");
                    }
                }
            });
        }
    }

    public void pauseNativeAudioFromWeb() {
        try {
            if (mPlayer != null && mPlayer.isPlaying()) {
                mPlayer.pause();
                ttsHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mWebView != null) {
                            mWebView.loadUrl("javascript:if(window.AudioControl){window.AudioControl.onpause();}");
                        }
                    }
                });
            }
        } catch (Exception e) {
            toastFromJava("Pause Error: " + e.getMessage());
        }
    }

    public void resumeNativeAudioFromWeb() {
        try {
            if (mPlayer != null && !mPlayer.isPlaying()) {
                mPlayer.start();
                // Direct callback to update your UI button to playing mode (❚❚)
                ttsHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mWebView != null) {
                            mWebView.loadUrl("javascript:if(window.AudioControl){window.AudioControl.onplay();}");
                        }
                    }
                });
            }
        } catch (Exception e) {
            toastFromJava("Resume Error: " + e.getMessage());
        }
    }

    public void stopNativeAudioFromWeb() {
        try {
            if (mPlayer != null) {
                mPlayer.stop();
                mPlayer.release();
                mPlayer = null; // Cleaned up the structural pause call crash from here!
                
                ttsHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mWebView != null) {
                            mWebView.loadUrl("javascript:if(window.AudioControl){window.AudioControl.onpause();}");
                        }
                    }
                });
            }
        } catch (Exception e) {
            toastFromJava("Stop Error: " + e.getMessage());
        }
    }
    
    // Helper method to clear the app's cache directory
    private void clearCacheFolder() {
        try {
            java.io.File dir = getCacheDir();
            if (dir != null && dir.isDirectory()) {
                java.io.File[] children = dir.listFiles();
                if (children != null) {
                    for (java.io.File child : children) {
                        child.delete();
                    }
                }
            }
        } catch (Exception e) {}
    }
    public void setNativeWakeLock(final boolean acquire) {
    ttsHandler.post(new Runnable() {
        @Override
        public void run() {
            if (acquire) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
    });
}

    @Override
    protected void onDestroy() {
        if (mTTS != null) {
            mTTS.stop();
            mTTS.shutdown();
        }
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
        if (mBgPlayer != null) {
            mBgPlayer.release();
            mBgPlayer = null;
        }
        if (mSoundPlayer != null) {
            mSoundPlayer.release();
            mSoundPlayer = null;
        }
        clearCacheFolder();
        super.onDestroy();
    }
    
    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WRITE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                writeTextToUri(data.getData(), pendingData);
            }
        }
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            Uri selectedFileUri = (data != null && resultCode == Activity.RESULT_OK) ? data.getData() : null;
            if (selectedFileUri != null && mWebView != null) {
                processAndPassFileToWeb(selectedFileUri);
            }
        }
        if (requestCode == 45 && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null && !pendingAudioPath.isEmpty()) {
                writeAudioToUri(data.getData(), pendingAudioPath);
            }
        }
    }

    private void writeAudioToUri(Uri targetUri, String sourcePath) {
        try {
            java.io.File sourceFile = new java.io.File(sourcePath);
            if (!sourceFile.exists()) return;

            InputStream is = new java.io.FileInputStream(sourceFile);
            OutputStream os = getContentResolver().openOutputStream(targetUri);
            
            if (os != null) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
                os.close();
                is.close();
                
                sourceFile.delete();
                pendingAudioPath = "";
                
                ttsHandler.post(new ToastRunnable(this, "Audio file saved successfully!"));
            }
        } catch (IOException e) {
            ttsHandler.post(new ToastRunnable(this, "Failed to save audio file."));
        }
    }

    private void writeTextToUri(Uri uri, String text) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) {
                os.write(text.getBytes("UTF-8"));
                os.close();
                ttsHandler.post(new ToastRunnable(this, "File saved successfully!"));
            }
        } catch (IOException e) {
            ttsHandler.post(new ToastRunnable(this, "Failed to save file."));
        }
    }
/*
    private void processAndPassFileToWeb(Uri uri) {
        String fileName = "unknown.file";
        long fileSize = 0;
        
        String mimeType = getContentResolver().getType(uri);
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIndex != -1) fileName = cursor.getString(nameIndex);
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex);
                }
            } finally {
                cursor.close();
            }
        }

try {
    InputStream is = getContentResolver().openInputStream(uri);
    if (is == null) return;

    // Check if it's JSON or HTML text data
    boolean isJson = "application/json".equals(mimeType) || "text/json".equals(mimeType) || fileName.endsWith(".json");
    boolean isHtml = "text/html".equals(mimeType) || fileName.endsWith(".html") || fileName.endsWith(".htm");

    if (isJson || isHtml) {
        // 1. Read directly as text stream to bypass Base64 overhead
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        is.close();
        
        // Save the raw text directly!
        lastPickedFileDataUrl = sb.toString(); 
        
    } else {
        // 2. Fallback for binary data (Images and Audio assets)
        byte[] bytes = new byte[is.available()];
        is.read(bytes);
        is.close();
        
        String base64String = Base64.encodeToString(bytes, Base64.NO_WRAP);
        lastPickedFileDataUrl = "data:" + mimeType + ";base64," + base64String;
    }
    
    // Notify JavaScript that the transfer metadata is ready
    ttsHandler.post(new FileTransferRunnable(mWebView, fileName, fileSize));
    
} catch (Exception e) {
    alertFromJava("Read Error: " + e.getMessage());
}

    }
    */
    
    private void processAndPassFileToWeb(Uri uri) {
        String tempName = "unknown.file";
        long tempSize = 0;
        
        String mimeType = getContentResolver().getType(uri);
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIndex != -1) tempName = cursor.getString(nameIndex);
                    if (sizeIndex != -1) tempSize = cursor.getLong(sizeIndex);
                }
            } finally {
                cursor.close();
            }
        }

        final String fileName = tempName;
        final long fileSize = tempSize;

        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return;

            boolean isJson = "application/json".equals(mimeType) || "text/json".equals(mimeType) || fileName.endsWith(".json");
            boolean isHtml = "text/html".equals(mimeType) || fileName.endsWith(".html") || fileName.endsWith(".htm");

            lastPickedFileDataUrl = ""; // Clear old data

            if (isJson || isHtml) {
                // 1. Read directly as plain text string
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                }
                reader.close();
                lastPickedFileDataUrl = sb.toString();
            } else {
                // 2. Read binary as a single Base64 Data URL string
                java.io.ByteArrayOutputStream byteBuffer = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    byteBuffer.write(buffer, 0, len);
                }
                byte[] bytes = byteBuffer.toByteArray();
                String base64String = Base64.encodeToString(bytes, Base64.NO_WRAP);
                lastPickedFileDataUrl = "data:" + mimeType + ";base64," + base64String;
            }
            is.close();
            
            // Notify WebView that metadata is ready
            ttsHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mWebView != null) {
                        mWebView.loadUrl("javascript:window.onAndroidMetadataPicked('" 
                            + fileName.replace("'", "\\'") + "', " + fileSize + ");");
                    }
                }
            });
            
        } catch (Exception e) {
        toastFromJava("Read Error: " + e.getMessage());
    }
    }

    private static class JavaScriptAlertRunnable implements Runnable {
        private final WebView webView;
        private final String message;

        public JavaScriptAlertRunnable(WebView webView, String message) {
            this.webView = webView;
            this.message = message;
        }

        @Override
        public void run() {
            webView.loadUrl("javascript:window.alert('Java Error: " + message + "');");
        }
    }

    public static class FileTransferRunnable implements Runnable {
        private final WebView webView;
        private final String fileName;
        private final long fileSize;

        public FileTransferRunnable(WebView webView, String fileName, long fileSize) {
            this.webView = webView;
            this.fileName = fileName.replace("'", "\\'"); 
            this.fileSize = fileSize;
        }

        @Override
        public void run() {
            if (webView != null) {
                webView.loadUrl("javascript:window.onAndroidMetadataPicked("
                    + "'" + fileName + "', "
                    + fileSize + ");");
            }
        }
    }

    public static class ToastRunnable implements Runnable {
        private final Activity activity;
        private final String message;

        public ToastRunnable(Activity activity, String message) {
            this.activity = activity;
            this.message = message;
        }

        @Override
        public void run() {
            if (activity != null) {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static class FileHandler {
        private final MainActivity mActivity;
        
        public FileHandler(MainActivity activity) { 
            mActivity = activity; 
        }
        /*
        @JavascriptInterface
        public String getPickedFileData() {
            String data = mActivity.lastPickedFileDataUrl;
            mActivity.lastPickedFileDataUrl = ""; 
            return data;
        }
        */
        @JavascriptInterface
        public int getFileChunkCount(int chunkSize) {
            if (chunkSize <= 0 || mActivity.lastPickedFileDataUrl == null) return 0;
            return (int) Math.ceil((double) mActivity.lastPickedFileDataUrl.length() / chunkSize);
        }

        @JavascriptInterface
        public String getFileChunk(int chunkIndex, int chunkSize) {
            if (mActivity.lastPickedFileDataUrl == null) return "";
            try {
                int start = chunkIndex * chunkSize;
                int end = Math.min(start + chunkSize, mActivity.lastPickedFileDataUrl.length());
                if (start >= mActivity.lastPickedFileDataUrl.length()) return "";
                return mActivity.lastPickedFileDataUrl.substring(start, end);
            } catch (Exception e) {
                return "";
            }
        }
        
        @JavascriptInterface
        public void clearNativeFileCache() {
            mActivity.lastPickedFileDataUrl = "";
        }
//== audio engine
// --- Background Track Control ---
@JavascriptInterface
public void nativePlayBg(String base64Data) {
    mActivity.playBgAudioFromBase64(base64Data);
}

@JavascriptInterface
public void nativeStopBg() {
    mActivity.stopBgAudio();
}

// --- Sound Effect Control ---
@JavascriptInterface
public void nativePlaySound(String base64Data) {
    mActivity.playAudioFromBase64(base64Data);
}

@JavascriptInterface
public void nativeStopSound() {
    mActivity.stopAudio();
}

// --- Engine-Wide States ---

@JavascriptInterface
public void nativePauseAll() {
    mActivity.pauseAllAudios();
}

@JavascriptInterface
public void nativeResumeAll() {
    mActivity.resumeAllAudios();
}

@JavascriptInterface
public void nativeStopAll() {
    mActivity.stopAllAudios();
}

// --- Oscillator Tones (Beep/Tick Native Mode) ---
@JavascriptInterface
public void nativePlayTone(int frequency, double durationSeconds) {
    mActivity.playNativeTone(frequency, durationSeconds);
}
//== audio
        @JavascriptInterface
        public void playNativeAudio(String base64Data) {
            mActivity.playNativeAudioFromBase64(base64Data);
        }
        @JavascriptInterface
        public void pauseNativeAudio() {
            mActivity.pauseNativeAudioFromWeb();
        }
        @JavascriptInterface
        public void resumeNativeAudio() {
            mActivity.resumeNativeAudioFromWeb();
        }
        @JavascriptInterface
        public void stopNativeAudio() {
            mActivity.stopNativeAudioFromWeb();
        }

//== audio end

        
        @JavascriptInterface 
        public void saveFile(String data, String filename) {
            if (!filename.endsWith(".json") && !filename.endsWith(".html")) {
                return;
            }

            String mimeType = filename.endsWith(".json") ? "application/json" : "text/html";
            mActivity.pendingData = data; 
            
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_TITLE, filename);
            try {
                mActivity.startActivityForResult(intent, WRITE_REQUEST_CODE);
            } catch (Exception e) {}
        }
@JavascriptInterface
public void triggerFallbackPicker(String filetype) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    
    if (filetype.equals("application/json")) {
        // Check if the device is Android 5.0 (API level 21) or newer
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            intent.setType("*/*");
            String[] mimeTypes = {"application/json", "text/json", "text/plain"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        } else {
            // Fallback for Android 4.4.2: 
            intent.setType("*/*");
        }
        
    } else if (filetype.equals("text/html")) {
        intent.setType("text/html");
        
    } else {
        // Naturally catches "image/*" or "audio/*"
        intent.setType(filetype);
    }
    
    try {
        mActivity.startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
    } catch (Exception e) {
        mActivity.toastFromJava("Picker Error: " + e.getMessage());
    }
}

        @JavascriptInterface
        public void speakText(String text, String lang) {
            mActivity.speak(text, lang);
        }
        
        @JavascriptInterface
        public void stopNativeTTS() {
            mActivity.ttsHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mActivity.mTTS != null) {
                        try {
                            mActivity.mTTS.stop(); // Instantly stops native audio synthesis
                        } catch (Exception e) {
                            // Safe catch block to prevent crashes if TTS isn't ready
                        }
                    }
                    // Force unducking as a safety fallback when clearing speech
                    mActivity.duck(false); 
                }
            });
        }

        @JavascriptInterface
        public void saveTtsToFile(String text, String filename) {
            mActivity.saveTts(text, filename);
        }
        
        @JavascriptInterface
        public void setWakeLock(boolean enable) {
            mActivity.setNativeWakeLock(enable);
        }
    }
}