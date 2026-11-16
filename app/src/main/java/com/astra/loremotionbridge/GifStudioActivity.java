package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import com.quramsoft.agifEncoder.QuramAGIFEncoder;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class GifStudioActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Uri> selected = new ArrayList<>();
    private TextView status, delayLabel;
    private GifPreviewView preview;
    private Button createButton;
    private int delayMs = 100;
    private Uri exportedUri;
    private File exportedFile;
    private static final int REQUEST_IMAGES = 7312;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8,5,16));
        getWindow().setNavigationBarColor(Color.rgb(8,5,16));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(16),dp(18),dp(18));
        root.setBackgroundColor(Color.rgb(8,5,16));
        root.addView(label("ASTRA  /  GIF STUDIO",22,true));
        root.addView(label("Собери анимацию из изображений",14,false));
        preview = new GifPreviewView();
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1,0,1f);
        pp.topMargin=dp(14); pp.bottomMargin=dp(12); root.addView(preview,pp);
        root.addView(button("＋  Выбрать кадры",this::chooseImages),fullWidth());
        status=label("Выбери от 2 до 30 изображений. Порядок — как в выборе файлов.",13,false);
        root.addView(status);
        delayLabel=label("Задержка кадра: 100 мс",13,false); root.addView(delayLabel);
        SeekBar seek=new SeekBar(this); seek.setMax(980); seek.setProgress(80);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){delayMs=p+20;delayLabel.setText("Задержка кадра: "+delayMs+" мс · "+Math.round(1000f/delayMs)+" FPS (примерно)");}
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        }); root.addView(seek);
        createButton=button("СОЗДАТЬ GIF",this::createGif); root.addView(createButton,fullWidth());
        root.addView(button("Поделиться готовым GIF",this::shareGif),fullWidth());
        root.addView(button("←  Назад в ASTRA CHAT",this::finish),fullWidth());
        setContentView(root);
    }
    private void chooseImages(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,REQUEST_IMAGES);
    }
    @Override @Deprecated protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=REQUEST_IMAGES||result!=RESULT_OK||data==null)return;
        selected.clear();
        try{
            if(data.getClipData()!=null){for(int n=0;n<data.getClipData().getItemCount();n++)selected.add(data.getClipData().getItemAt(n).getUri());}
            else if(data.getData()!=null)selected.add(data.getData());
            for(Uri u:selected){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}}
            while(selected.size()>30)selected.remove(selected.size()-1);
            status.setText(selected.size()>=2?"Выбрано кадров: "+selected.size():"Нужно выбрать минимум 2 изображения.");
        }catch(Exception e){status.setText("Не удалось прочитать выбор: "+e.getMessage());}
    }
    private void createGif(){
        if(selected.size()<2||selected.size()>30){status.setText("Выбери от 2 до 30 изображений.");return;}
        createButton.setEnabled(false); status.setText("Подготовка кадров…");
        final ArrayList<Uri> uris=new ArrayList<>(selected); final int delay=delayMs;
        worker.execute(()->{
            QuramAGIFEncoder encoder=null; boolean started=false; ArrayList<Bitmap> bitmaps=new ArrayList<>();
            try{
                ArrayList<Bitmap> frames=new ArrayList<>();
                for(int n=0;n<uris.size();n++){
                    final int ix=n+1; runOnUiThread(()->status.setText("Чтение кадра "+ix+"/"+uris.size()+"…"));
                    InputStream in=getContentResolver().openInputStream(uris.get(n));
                    if(in==null)throw new IOException("Не удалось открыть изображение "+ix);
                    Bitmap decoded; try{decoded=BitmapFactory.decodeStream(in);}finally{in.close();}
                    if(decoded==null)throw new IOException("Не удалось декодировать кадр "+ix);
                    Bitmap fitted=fitBitmap(decoded,640); if(fitted!=decoded)decoded.recycle();
                    bitmaps.add(fitted);frames.add(fitted);
                }
                File dir=new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES)!=null?getExternalFilesDir(Environment.DIRECTORY_PICTURES):getFilesDir(),"ASTRA");
                if(!dir.exists()&&!dir.mkdirs())throw new IOException("Не удалось создать папку результата");
                File output=new File(dir,"ASTRA_"+System.currentTimeMillis()+".gif");
                encoder=new QuramAGIFEncoder();
                if(!encoder.start(output.getAbsolutePath()))throw new IOException("Samsung encoder не смог открыть выходной файл");
                started=true;encoder.setDelay(delay);encoder.setRepeat(0);encoder.setMaxTaskTP(1);
                for(int n=0;n<frames.size();n++){
                    final int ix=n+1;runOnUiThread(()->status.setText("Кодирование кадра "+ix+"/"+frames.size()+"…"));
                    if(!encoder.addFrameTP(frames.get(n)))throw new IOException("Samsung encoder отклонил кадр "+ix);
                }
                if(!encoder.finish())throw new IOException("Samsung encoder сообщил об ошибке завершения");
                started=false; Uri gallery=publishToGallery(output);
                exportedFile=output;exportedUri=gallery;
                runOnUiThread(()->{preview.setGif(output);status.setText("Готово! "+frames.size()+" кадров · "+(output.length()/1024)+" КБ\nСохранено в Pictures/ASTRA.");createButton.setEnabled(true);});
            }catch(Throwable t){
                if(started&&encoder!=null)try{encoder.finish();}catch(Throwable ignored){}
                String message=(t instanceof UnsatisfiedLinkError||t instanceof ExceptionInInitializerError)
                    ?"Samsung Quram Encoder недоступен этому приложению на данном устройстве."
                    :"Не удалось создать GIF: "+(t.getMessage()!=null?t.getMessage():t.getClass().getSimpleName());
                runOnUiThread(()->{status.setText(message);createButton.setEnabled(true);});
            }finally{for(Bitmap b:bitmaps)if(!b.isRecycled())b.recycle();}
        });
    }
    private Bitmap fitBitmap(Bitmap src,int maxSide){
        float scale=Math.min(1f,(float)maxSide/Math.max(src.getWidth(),src.getHeight()));
        int w=Math.max(1,Math.round(src.getWidth()*scale)),h=Math.max(1,Math.round(src.getHeight()*scale));
        Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src,null,new Rect(0,0,w,h),new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG));return out;
    }
    private Uri publishToGallery(File file)throws IOException{
        if(Build.VERSION.SDK_INT<29)return null;
        ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,file.getName());
        v.put(MediaStore.Images.Media.MIME_TYPE,"image/gif");v.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/ASTRA");
        v.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
        if(uri==null)throw new IOException("Не удалось создать запись в Галерее");
        try{
            OutputStream out=getContentResolver().openOutputStream(uri);if(out==null)throw new IOException("Не удалось сохранить GIF в Галерею");
            try(InputStream in=new FileInputStream(file);OutputStream dst=out){byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)dst.write(buf,0,n);}
            ContentValues done=new ContentValues();done.put(MediaStore.Images.Media.IS_PENDING,0);getContentResolver().update(uri,done,null,null);return uri;
        }catch(Throwable e){getContentResolver().delete(uri,null,null);if(e instanceof IOException)throw(IOException)e;throw new IOException(e);}
    }
    private void shareGif(){
        if(exportedUri==null){status.setText(exportedFile==null?"Сначала создай GIF.":"GIF создан в папке приложения; публикация в Галерее недоступна.");return;}
        Intent i=new Intent(Intent.ACTION_SEND);i.setType("image/gif");i.putExtra(Intent.EXTRA_STREAM,exportedUri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i,"Поделиться GIF"));
    }
    private TextView label(String s,int size,boolean bold){return label(s,size,bold?Color.rgb(229,199,255):Color.rgb(220,210,235),bold);}
    private TextView label(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,1);t.setPadding(0,dp(7),0,dp(7));return t;}
    private Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackgroundColor(Color.rgb(83,31,125));b.setOnClickListener(v->action.run());return b;}
    private LinearLayout.LayoutParams fullWidth(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.bottomMargin=dp(7);return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}

    private final class GifPreviewView extends View{
        private Movie movie;private long startedAt;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        GifPreviewView(){super(GifStudioActivity.this);}
        void setGif(File file){try(InputStream in=new FileInputStream(file)){movie=Movie.decodeStream(in);}catch(IOException e){movie=null;}startedAt=SystemClock.uptimeMillis();invalidate();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);if(movie==null)return;int duration=Math.max(1,movie.duration());movie.setTime((int)((SystemClock.uptimeMillis()-startedAt)%duration));
            float scale=Math.min((float)getWidth()/movie.width(),(float)getHeight()/movie.height());float w=movie.width()*scale,h=movie.height()*scale;
            movie.draw(c,(getWidth()-w)/2f,(getHeight()-h)/2f,paint);postInvalidateDelayed(40);}
    }
}