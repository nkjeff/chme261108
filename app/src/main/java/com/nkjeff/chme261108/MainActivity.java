package com.nkjeff.chme261108;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final int BLACK=Color.BLACK, WHITE=Color.WHITE, GRAY=0xff888888;
    private LinearLayout root, tabs, toolbar, selectionBar;
    private GridView grid;
    private MediaAdapter adapter;
    private final ArrayList<MediaItem> items=new ArrayList<>();
    private final LinkedHashSet<Long> selected=new LinkedHashSet<>();
    private final ArrayList<String> customTags=new ArrayList<>();
    private String activeTag="全部";
    private String sortMode="添加时间";
    private int columns=4;
    private boolean selecting=false, dragging=false;
    private float downX, downY;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private static final int REQ_MEDIA=100;

    static class MediaItem {
        long id, date, size; boolean video, trashed; Uri uri;
        MediaItem(long i,long d,long s,boolean v,boolean t,Uri u){id=i;date=d;size=s;video=v;trashed=t;uri=u;}
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BLACK); getWindow().setNavigationBarColor(BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        loadTags();
        if(hasPermission()) loadMedia(); else requestMedia();
    }

    private boolean hasPermission(){
        if(Build.VERSION.SDK_INT>=33) return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)==PackageManager.PERMISSION_GRANTED;
        return Build.VERSION.SDK_INT<23 || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestMedia(){
        if(Build.VERSION.SDK_INT>=34) requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED},REQ_MEDIA);
        else if(Build.VERSION.SDK_INT>=33) requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO},REQ_MEDIA);
        else if(Build.VERSION.SDK_INT>=23) requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},REQ_MEDIA);
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQ_MEDIA)loadMedia();}

    private TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextColor(WHITE);v.setTextSize(size);v.setGravity(Gravity.CENTER);v.setPadding(dp(8),dp(7),dp(8),dp(7));return v;}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void buildUi(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BLACK);setContentView(root);
        toolbar=row(); toolbar.setPadding(dp(8),dp(2),dp(8),dp(2)); root.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(48)));
        addTool("⌕",()->showSearch()); addTool("⇅",()->showSort()); addTool("▦",()->showColumns()); addTool("＋",()->showAddTag());
        tabs=row(); ScrollView tabScroll=new ScrollView(this);tabScroll.setHorizontalScrollBarEnabled(false);tabScroll.addView(tabs);root.addView(tabScroll,new LinearLayout.LayoutParams(-1,dp(43)));
        LinearLayout controls=row();controls.setPadding(dp(10),0,dp(10),0);root.addView(controls,new LinearLayout.LayoutParams(-1,dp(34)));
        TextView sort=text("添加时间 ↓",12);sort.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);controls.addView(sort,new LinearLayout.LayoutParams(0,-1,1));sort.setOnClickListener(v->showSort());
        TextView density=text("4列   8列   16列",12);controls.addView(density);density.setOnClickListener(v->showColumns());
        grid=new GridView(this);grid.setNumColumns(columns);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setHorizontalSpacing(dp(2));grid.setVerticalSpacing(dp(2));grid.setPadding(dp(2),dp(2),dp(2),dp(2));grid.setClipToPadding(false);grid.setBackgroundColor(BLACK);
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        selectionBar=row();selectionBar.setGravity(Gravity.CENTER);selectionBar.setVisibility(View.GONE);root.addView(selectionBar,new LinearLayout.LayoutParams(-1,dp(52)));
        adapter=new MediaAdapter();grid.setAdapter(adapter);
        grid.setOnItemClickListener((a,v,pos,id)->{if(pos>=items.size())return;MediaItem m=items.get(pos);if(selecting)toggle(m.id);else openMedia(m);});
        grid.setOnItemLongClickListener((a,v,pos,id)->{if(pos<items.size()){selecting=true;toggle(items.get(pos).id);refreshSelectionBar();}return true;});
        grid.setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();dragging=false;}
            else if(e.getAction()==MotionEvent.ACTION_MOVE && selecting){int p=grid.pointToPosition((int)e.getX(),(int)e.getY());if(p>=0&&p<items.size()){dragging=true;long id=items.get(p).id;if(!selected.contains(id)){selected.add(id);adapter.notifyDataSetChanged();refreshSelectionBar();}}}
            return false;
        });
        renderTabs();
    }
    private void addTool(String glyph,Runnable action){TextView b=text(glyph,23);toolbar.addView(b,new LinearLayout.LayoutParams(0,-1,1));b.setOnClickListener(v->action.run());}
    private void loadTags(){android.content.SharedPreferences p=getSharedPreferences("tags",0);customTags.clear();customTags.addAll(p.getStringSet("custom",new HashSet<>()));}
    private void saveTags(){getSharedPreferences("tags",0).edit().putStringSet("custom",new HashSet<>(customTags)).apply();}
    private void renderTabs(){
        tabs.removeAllViews();if(activeTag.equals("最近删除")){addTab("最近删除");return;}String[] fixed={"全部","视频","图片","最近删除"};
        for(String t:fixed)addTab(t);for(String t:customTags)addTab(t);
    }
    private void addTab(String name){TextView t=text(name,14);t.setTextColor(activeTag.equals(name)?WHITE:GRAY);if(activeTag.equals(name))t.setTypeface(null,Typeface.BOLD);tabs.addView(t);t.setOnClickListener(v->{if(name.equals("最近删除")){activeTag=name;selected.clear();selecting=false;refreshSelectionBar();}else activeTag=name;renderTabs();loadMedia();});}
    private void showAddTag(){EditText e=new EditText(this);e.setSingleLine();e.setHint("标签名称");e.setTextColor(WHITE);e.setHintTextColor(GRAY);new AlertDialog.Builder(this).setTitle("新增标签").setView(e).setNegativeButton("取消",null).setPositiveButton("添加",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()&&!Arrays.asList("全部","视频","图片","最近删除").contains(s)&&!customTags.contains(s)){customTags.add(s);saveTags();activeTag=s;renderTabs();loadMedia();}}).show();}
    private void showSort(){new AlertDialog.Builder(this).setTitle("排序方式").setItems(new String[]{"添加时间：新到旧","添加时间：旧到新","大小：大到小","大小：小到大"},(d,w)->{sortMode=w<2?"添加时间":"大小";sortDirection=(w==1||w==3)?1:0;loadMedia();}).show();}
    private int sortDirection=0;
    private void showColumns(){new AlertDialog.Builder(this).setTitle("每行数量").setItems(new String[]{"4 列","8 列","16 列"},(d,w)->{columns=new int[]{4,8,16}[w];grid.setNumColumns(columns);adapter.notifyDataSetChanged();}).show();}
    private void showSearch(){EditText e=new EditText(this);e.setSingleLine();e.setHint("搜索媒体文件名");new AlertDialog.Builder(this).setTitle("搜索").setView(e).setNegativeButton("关闭",null).setPositiveButton("搜索",(d,w)->{String q=e.getText().toString().trim().toLowerCase(Locale.ROOT);if(q.isEmpty())return;Toast.makeText(this,"搜索将在下一版完善",Toast.LENGTH_SHORT).show();}).show();}
    private void loadMedia(){
        executor.execute(()->{
            ArrayList<MediaItem> result=new ArrayList<>();
            if(Build.VERSION.SDK_INT>=23 && !hasPermission()){runOnUiThread(()->{items.clear();adapter.notifyDataSetChanged();});return;}
            Uri base=MediaStore.Files.getContentUri("external");
            String[] projection={MediaStore.Files.FileColumns._ID,MediaStore.Files.FileColumns.DATE_ADDED,MediaStore.Files.FileColumns.SIZE,MediaStore.Files.FileColumns.MEDIA_TYPE,MediaStore.Files.FileColumns.DISPLAY_NAME};
            StringBuilder where=new StringBuilder(MediaStore.Files.FileColumns.MEDIA_TYPE+" IN (?,?)");
            ArrayList<String> args=new ArrayList<>(Arrays.asList("1","3"));
            if(customTags.contains(activeTag)){Set<String> ids=getSharedPreferences("media_tags",0).getStringSet(activeTag,new HashSet<>());if(ids.isEmpty()){runOnUiThread(()->{items.clear();adapter.notifyDataSetChanged();});return;}where=new StringBuilder(MediaStore.Files.FileColumns._ID+" IN (");for(int j=0;j<ids.size();j++){if(j>0)where.append(",");where.append("?");args.add(ids.toArray(new String[0])[j]);}where.append(") AND ").append(MediaStore.Files.FileColumns.MEDIA_TYPE+" IN (?,?)");args.add("1");args.add("3");}\n            if(activeTag.equals("图片")){where=new StringBuilder(MediaStore.Files.FileColumns.MEDIA_TYPE+"=?");args.clear();args.add("1");}
            if(activeTag.equals("视频")){where=new StringBuilder(MediaStore.Files.FileColumns.MEDIA_TYPE+"=?");args.clear();args.add("3");}
            if(Build.VERSION.SDK_INT>=30){where.append(" AND ").append(MediaStore.MediaColumns.IS_TRASHED).append("=?");args.add(activeTag.equals("最近删除")?"1":"0");}
            else if(activeTag.equals("最近删除")){runOnUiThread(()->{items.clear();adapter.notifyDataSetChanged();});return;}
            String order=(sortMode.equals("添加时间")?MediaStore.Files.FileColumns.DATE_ADDED:MediaStore.Files.FileColumns.SIZE)+(sortDirection==1?" ASC":" DESC");
            try(Cursor c=getContentResolver().query(base,projection,where.toString(),args.toArray(new String[0]),order)){
                if(c!=null)while(c.moveToNext()){long id=c.getLong(0);long date=c.getLong(1);long size=c.getLong(2);int type=c.getInt(3);Uri u=ContentUris.withAppendedId(type==3?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id);result.add(new MediaItem(id,date,size,type==3,activeTag.equals("最近删除"),u));}
            }catch(Exception ignored){}
            runOnUiThread(()->{items.clear();items.addAll(result);adapter.notifyDataSetChanged();});
        });
    }
    private void toggle(long id){if(selected.contains(id))selected.remove(id);else selected.add(id);if(selected.isEmpty())selecting=false;adapter.notifyDataSetChanged();refreshSelectionBar();}
    private void refreshSelectionBar(){selectionBar.removeAllViews();selectionBar.setVisibility(selecting?View.VISIBLE:View.GONE);if(!selecting)return;TextView count=text(selected.size()+" 已选择",13);selectionBar.addView(count);if(!activeTag.equals("最近删除")&&!customTags.isEmpty()){TextView tag=text("加入标签",13);selectionBar.addView(tag);tag.setOnClickListener(v->assignSelectedTag());}TextView del=text(activeTag.equals("最近删除")?"永久删除":"移入最近删除",13);selectionBar.addView(del);del.setOnClickListener(v->deleteSelected());TextView cancel=text("取消",13);selectionBar.addView(cancel);cancel.setOnClickListener(v->{selected.clear();selecting=false;adapter.notifyDataSetChanged();refreshSelectionBar();});}
    private void assignSelectedTag(){new AlertDialog.Builder(this).setTitle("添加到标签").setItems(customTags.toArray(new String[0]),(d,w)->{String tag=customTags.get(w);android.content.SharedPreferences p=getSharedPreferences("media_tags",0);Set<String> ids=new HashSet<>(p.getStringSet(tag,new HashSet<>()));for(Long id:selected)ids.add(String.valueOf(id));p.edit().putStringSet(tag,ids).apply();selected.clear();selecting=false;refreshSelectionBar();Toast.makeText(this,"已添加到 "+tag,Toast.LENGTH_SHORT).show();}).show();}\n    private void deleteSelected(){new AlertDialog.Builder(this).setMessage(activeTag.equals("最近删除")?"永久删除所选媒体？":"将所选媒体移入最近删除？").setNegativeButton("取消",null).setPositiveButton("确定",(d,w)->{for(MediaItem m:new ArrayList<>(items))if(selected.contains(m.id)){try{if(Build.VERSION.SDK_INT>=30){ContentValues cv=new ContentValues();cv.put(MediaStore.MediaColumns.IS_TRASHED,activeTag.equals("最近删除")?0:1);getContentResolver().update(m.uri,cv,null,null);}else if(!activeTag.equals("最近删除")){ContentValues cv=new ContentValues();cv.put(MediaStore.MediaColumns.IS_PENDING,1);getContentResolver().update(m.uri,cv,null,null);}}catch(Exception ignored){}}selected.clear();selecting=false;refreshSelectionBar();loadMedia();}).show();}
    private void openMedia(MediaItem m){try{Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(m.uri,m.video?"video/*":"image/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i);overridePendingTransition(0,0);}catch(Exception ignored){}}
    @Override public void onBackPressed(){if(selecting){selected.clear();selecting=false;adapter.notifyDataSetChanged();refreshSelectionBar();}else if(!activeTag.equals("全部")){activeTag="全部";renderTabs();loadMedia();}else super.onBackPressed();}

    class MediaAdapter extends BaseAdapter {
        @Override public int getCount(){return items.size();}
        @Override public Object getItem(int p){return items.get(p);}
        @Override public long getItemId(int p){return items.get(p).id;}
        @Override public View getView(int p,View convert,ViewGroup parent){
            FrameLayout frame=convert instanceof FrameLayout?(FrameLayout)convert:new FrameLayout(MainActivity.this);
            ImageView image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.removeAllViews();frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
            int side=getResources().getDisplayMetrics().widthPixels/columns;
            frame.setLayoutParams(new AbsListView.LayoutParams(-1,side));
            MediaItem m=items.get(p);
            try{if(Build.VERSION.SDK_INT>=29)image.setImageBitmap(getContentResolver().loadThumbnail(m.uri,new android.util.Size(Math.max(64,side),Math.max(64,side)),null));else image.setImageURI(m.uri);}catch(Exception e){image.setBackgroundColor(0xff202020);}
            if(m.video){TextView play=text("▶",12);play.setBackgroundColor(0x55000000);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.BOTTOM|Gravity.RIGHT);frame.addView(play,lp);}
            if(selected.contains(m.id)){View shade=new View(MainActivity.this);shade.setBackgroundColor(0x66000000);frame.addView(shade,new FrameLayout.LayoutParams(-1,-1));TextView check=text("✓",18);frame.addView(check,new FrameLayout.LayoutParams(-1,-1));}
            return frame;
        }
    }
}
