package com.nkjeff.chme261108;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.VideoView;
import java.io.InputStream;

public class MediaViewerActivity extends Activity {
    private boolean barsHidden=true;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();

        String value=getIntent().getStringExtra("media_uri");
        boolean video=getIntent().getBooleanExtra("media_video",false);
        if(value==null) { finish(); return; }
        Uri uri=Uri.parse(value);

        FrameLayout container=new FrameLayout(this);
        container.setBackgroundColor(Color.BLACK);
        setContentView(container);

        if(video) {
            VideoView player=new VideoView(this);
            container.addView(player,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
            MediaController controls=new MediaController(this);
            controls.setAnchorView(player);
            player.setMediaController(controls);
            player.setVideoURI(uri);
            player.setOnPreparedListener(mediaPlayer->{
                mediaPlayer.setOnVideoSizeChangedListener((mp,width,height)->{
                    player.setLayoutParams(new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
                });
                player.start();
            });
            player.setOnErrorListener((mp,what,extra)->{finish();return true;});
            player.setOnClickListener(v->{ if(player.isPlaying()) player.pause(); else player.start(); });
        } else {
            ImageView image=new ImageView(this);
            image.setBackgroundColor(Color.BLACK);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            container.addView(image,new FrameLayout.LayoutParams(-1,-1));
            loadImage(uri,image);
            image.setOnClickListener(v->{
                if(barsHidden) { showSystemBars(); barsHidden=false; }
                else { hideSystemBars(); barsHidden=true; }
            });
        }
    }

    private void loadImage(Uri uri,ImageView target) {
        try {
            BitmapFactory.Options bounds=new BitmapFactory.Options();
            bounds.inJustDecodeBounds=true;
            try(InputStream in=getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in,null,bounds);
            }
            int width=Math.max(1,getResources().getDisplayMetrics().widthPixels);
            int height=Math.max(1,getResources().getDisplayMetrics().heightPixels);
            int sample=1;
            while(bounds.outWidth/sample>width*2 || bounds.outHeight/sample>height*2) sample*=2;
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inSampleSize=sample;
            try(InputStream in=getContentResolver().openInputStream(uri)) {
                Bitmap bitmap=BitmapFactory.decodeStream(in,null,options);
                target.setImageBitmap(bitmap);
            }
        } catch(Exception error) {
            finish();
        }
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }
    private void showSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void finish() {
        super.finish();
        overridePendingTransition(0,0);
    }
}
