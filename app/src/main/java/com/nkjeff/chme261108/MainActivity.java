package com.nkjeff.chme261108;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
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
    private final int WHITE=Color.WHITE, GRAY=0xff888888;
    private LinearLayout root, tabs, toolbar, selectionBar;
    private GridView grid;
    private MediaAdapter adapter;
    private final ArrayList<MediaItem> items=new ArrayList<>();
    private final LinkedHashSet<Long> selected=new LinkedHashSet<>();
    private final ArrayList<String> customTags=new ArrayList<>();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private String activeTag="全部", sortMode="添加时间", filterQuery="";
    private int sortDirection=0, columns=4;
    private boolean selecting=false;
    private static final int REQ_MEDIA=100, REQ_TRASH=101, REQ_DELETE=102;

    static class MediaItem {
        long id, date, size;
        boolean video, trashed;
        Uri uri;
        MediaItem(long id,long date,long size,boolean video,boolean trashed,Uri uri) {
            this.id=id; this.date=date; this.size=size; this.video=video; this.trashed=trashed; this.uri=uri;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        loadTags();
        if(hasAnyMediaPermission()) loadMedia(); else requestMedia();
    }

    private boolean hasAnyMediaPermission() {
        if(Build.VERSION.SDK_INT>=33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)==PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT>=34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED);
        }
        return Build.VERSION.SDK_INT<23 || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }

    private void requestMedia() {
        if(Build.VERSION.SDK_INT>=34) requestPermissions(new String[]{
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        },REQ_MEDIA);
        else if(Build.VERSION.SDK_INT>=33) requestPermissions(new String[]{
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO
        },REQ_MEDIA);
        else if(Build.VERSION.SDK_INT>=23) requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},REQ_MEDIA);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==REQ_MEDIA) loadMedia();
    }

    private int dp(float value) { return (int)(value*getResources().getDisplayMetrics().density+0.5f); }
    private TextView text(String value,int size) {
        TextView view=new TextView(this);
        view.setText(value); view.setTextColor(WHITE); view.setTextSize(size);
        view.setGravity(Gravity.CENTER); view.setPadding(dp(8),dp(7),dp(8),dp(7));
        return view;
    }
    private LinearLayout row() {
        LinearLayout view=new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void buildUi() {
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        toolbar=row(); toolbar.setPadding(dp(8),dp(2),dp(8),dp(2));
        root.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(48)));
        addTool("⌕",this::showSearch);
        addTool("⇅",this::showSort);
        addTool("▦",this::showColumns);
        addTool("＋",this::showAddTag);

        tabs=row();
        ScrollView tabScroll=new ScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabs);
        root.addView(tabScroll,new LinearLayout.LayoutParams(-1,dp(43)));

        LinearLayout controls=row();
        controls.setPadding(dp(10),0,dp(10),0);
        root.addView(controls,new LinearLayout.LayoutParams(-1,dp(34)));
        TextView sort=text(sortLabel(),12);
        sort.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
        controls.addView(sort,new LinearLayout.LayoutParams(0,-1,1));
        sort.setOnClickListener(v->showSort());
        TextView density=text(columns+"列",12);
        controls.addView(density);
        density.setOnClickListener(v->showColumns());

        grid=new GridView(this);
        grid.setNumColumns(columns);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        grid.setPadding(dp(2),dp(2),dp(2),dp(2));
        grid.setClipToPadding(false);
        grid.setBackgroundColor(Color.BLACK);
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));

        selectionBar=row();
        selectionBar.setGravity(Gravity.CENTER);
        selectionBar.setVisibility(View.GONE);
        root.addView(selectionBar,new LinearLayout.LayoutParams(-1,dp(52)));

        adapter=new MediaAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent,view,position,id)->{
            if(position<0||position>=items.size()) return;
            MediaItem item=items.get(position);
            if(selecting) toggle(item.id); else openMedia(item);
        });
        grid.setOnItemLongClickListener((parent,view,position,id)->{
            if(position>=0&&position<items.size()) {
                selecting=true;
                if(!selected.contains(items.get(position).id)) selected.add(items.get(position).id);
                adapter.notifyDataSetChanged();
                refreshSelectionBar();
            }
            return true;
        });
        grid.setOnTouchListener((view,event)->{
            if(event.getAction()==MotionEvent.ACTION_MOVE && selecting) {
                int position=grid.pointToPosition((int)event.getX(),(int)event.getY());
                if(position>=0&&position<items.size()) {
                    long id=items.get(position).id;
                    if(selected.add(id)) {
                        adapter.notifyDataSetChanged();
                        refreshSelectionBar();
                    }
                }
            }
            return false;
        });
        renderTabs();
    }

    private String sortLabel() {
        return (sortMode.equals("添加时间")?"添加时间":"文件大小")+(sortDirection==0?" ↓":" ↑");
    }
    private void addTool(String glyph,Runnable action) {
        TextView button=text(glyph,23);
        toolbar.addView(button,new LinearLayout.LayoutParams(0,-1,1));
        button.setOnClickListener(v->action.run());
    }

    private void loadTags() {
        Set<String> stored=getSharedPreferences("tags",0).getStringSet("custom",new HashSet<>());
        customTags.clear(); customTags.addAll(stored);
    }
    private void saveTags() {
        getSharedPreferences("tags",0).edit().putStringSet("custom",new HashSet<>(customTags)).apply();
    }
    private void renderTabs() {
        tabs.removeAllViews();
        if(activeTag.equals("最近删除")) {
            addTab("最近删除");
            return;
        }
        for(String name:new String[]{"全部","视频","图片","最近删除"}) addTab(name);
        for(String name:customTags) addTab(name);
    }
    private void addTab(String name) {
        TextView tab=text(name,14);
        tab.setTextColor(activeTag.equals(name)?WHITE:GRAY);
        if(activeTag.equals(name)) tab.setTypeface(null,Typeface.BOLD);
        tabs.addView(tab);
        tab.setOnClickListener(v->{
            activeTag=name;
            selected.clear(); selecting=false;
            refreshSelectionBar();
            renderTabs();
            loadMedia();
        });
    }

    private void showAddTag() {
        EditText input=new EditText(this);
        input.setSingleLine(true); input.setHint("标签名称");
        input.setTextColor(WHITE); input.setHintTextColor(GRAY);
        new AlertDialog.Builder(this).setTitle("新增标签").setView(input)
            .setNegativeButton("取消",null)
            .setPositiveButton("添加",(dialog,which)->{
                String name=input.getText().toString().trim();
                if(!name.isEmpty()&&!Arrays.asList("全部","视频","图片","最近删除").contains(name)&&!customTags.contains(name)) {
                    customTags.add(name); saveTags(); activeTag=name; renderTabs(); loadMedia();
                }
            }).show();
    }

    private void showSort() {
        new AlertDialog.Builder(this).setTitle("排序方式")
            .setItems(new String[]{"添加时间：新到旧","添加时间：旧到新","大小：大到小","大小：小到大"},(dialog,which)->{
                sortMode=which<2?"添加时间":"大小";
                sortDirection=(which==1||which==3)?1:0;
                updateControlsAndReload();
            }).show();
    }
    private void showColumns() {
        new AlertDialog.Builder(this).setTitle("每行数量")
            .setItems(new String[]{"4 列","8 列","16 列"},(dialog,which)->{
                columns=new int[]{4,8,16}[which];
                grid.setNumColumns(columns);
                updateControls();
                adapter.notifyDataSetChanged();
            }).show();
    }
    private void showSearch() {
        EditText input=new EditText(this);
        input.setSingleLine(true); input.setText(filterQuery); input.setHint("搜索媒体文件名");
        new AlertDialog.Builder(this).setTitle("搜索媒体").setView(input)
            .setNeutralButton("清除",(dialog,which)->{filterQuery="";loadMedia();})
            .setNegativeButton("取消",null)
            .setPositiveButton("搜索",(dialog,which)->{filterQuery=input.getText().toString().trim();loadMedia();})
            .show();
    }
    private void updateControlsAndReload() { updateControls(); loadMedia(); }
    private void updateControls() {
        if(root.getChildCount()>2) {
            LinearLayout controls=(LinearLayout)root.getChildAt(2);
            TextView sort=(TextView)controls.getChildAt(0);
            TextView density=(TextView)controls.getChildAt(1);
            sort.setText(sortLabel()); density.setText(columns+"列");
        }
    }

    private void loadMedia() {
        final String tag=activeTag, query=filterQuery, sort=sortMode;
        final int direction=sortDirection;
        executor.execute(()->{
            ArrayList<MediaItem> result=new ArrayList<>();
            if(Build.VERSION.SDK_INT>=23&&!hasAnyMediaPermission()) {
                runOnUiThread(()->{items.clear();adapter.notifyDataSetChanged();});
                return;
            }

            Uri collection=MediaStore.Files.getContentUri("external");
            String[] projection={
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            };
            StringBuilder selection=new StringBuilder(MediaStore.Files.FileColumns.MEDIA_TYPE+" IN (?,?)");
            ArrayList<String> args=new ArrayList<>(Arrays.asList("1","3"));

            if(customTags.contains(tag)) {
                Set<String> tagIds=getSharedPreferences("media_tags",0).getStringSet(tag,new HashSet<>());
                if(tagIds.isEmpty()) {
                    runOnUiThread(()->{items.clear();adapter.notifyDataSetChanged();});
                    return;
                }
                selection=new StringBuilder(MediaStore.Files.FileColumns._ID+" IN (");
                args.clear();
                int index=0;
                for(String id:tagIds) {
                    if(index++>0) selection.append(",");
                    selection.append("?");
                    args.add(id);
                }
                selection.append(") AND ").append(MediaStore.Files.FileColumns.MEDIA_TYPE).append(" IN (?,?)");
                args.add("1"); args.add("3");
            } else if(tag.equals("图片")) {
                selection=new StringBuilder(MediaStore.Files.FileColumns.MEDIA_TYPE+"=?");
                args.clear(); args.add("1");
            } else if(tag.equals("视频")) {
                selection=new StringBuilder(MediaStore.Files.FileColumns.MEDIA_TYPE+"=?");
                args.clear(); args.add("3");
            }

            if(Build.VERSION.SDK_INT>=30) {
                selection.append(" AND ").append(MediaStore.MediaColumns.IS_TRASHED).append("=?");
                args.add(tag.equals("最近删除")?"1":"0");
            } else if(tag.equals("最近删除")) {
                runOnUiThread(()->{items.clear();adapter.notifyDataSetChanged();});
                return;
            }

            if(!query.isEmpty()) {
                selection.append(" AND ").append(MediaStore.Files.FileColumns.DISPLAY_NAME).append(" LIKE ?");
                args.add("%"+query+"%");
            }

            String order=(sort.equals("添加时间")?MediaStore.Files.FileColumns.DATE_ADDED:MediaStore.Files.FileColumns.SIZE)
                +(direction==0?" DESC":" ASC");
            try(Cursor cursor=getContentResolver().query(collection,projection,selection.toString(),args.toArray(new String[0]),order)) {
                if(cursor!=null) while(cursor.moveToNext()) {
                    long id=cursor.getLong(0), date=cursor.getLong(1), size=cursor.getLong(2);
                    int type=cursor.getInt(3);
                    boolean video=type==MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                    Uri uri=ContentUris.withAppendedId(video?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id);
                    result.add(new MediaItem(id,date,size,video,tag.equals("最近删除"),uri));
                }
            } catch(Exception error) {
                android.util.Log.e("MediaManager","Media query failed",error);
            }
            runOnUiThread(()->{
                if(!tag.equals(activeTag)||!query.equals(filterQuery)) return;
                items.clear(); items.addAll(result); adapter.notifyDataSetChanged();
            });
        });
    }

    private void toggle(long id) {
        if(selected.contains(id)) selected.remove(id); else selected.add(id);
        if(selected.isEmpty()) selecting=false;
        adapter.notifyDataSetChanged();
        refreshSelectionBar();
    }
    private void refreshSelectionBar() {
        selectionBar.removeAllViews();
        selectionBar.setVisibility(selecting?View.VISIBLE:View.GONE);
        if(!selecting) return;
        selectionBar.addView(text(selected.size()+" 已选择",13));
        if(activeTag.equals("最近删除")) {
            TextView restore=text("恢复",13); selectionBar.addView(restore); restore.setOnClickListener(v->changeTrashState(false));
            TextView permanent=text("永久删除",13); selectionBar.addView(permanent); permanent.setOnClickListener(v->permanentlyDelete());
        } else {
            if(!customTags.isEmpty()) {
                TextView tag=text("加入标签",13); selectionBar.addView(tag); tag.setOnClickListener(v->assignSelectedTag());
            }
            TextView trash=text("移入最近删除",13); selectionBar.addView(trash); trash.setOnClickListener(v->changeTrashState(true));
        }
        TextView cancel=text("取消",13); selectionBar.addView(cancel);
        cancel.setOnClickListener(v->{selected.clear();selecting=false;adapter.notifyDataSetChanged();refreshSelectionBar();});
    }

    private void assignSelectedTag() {
        new AlertDialog.Builder(this).setTitle("添加到标签")
            .setItems(customTags.toArray(new String[0]),(dialog,which)->{
                String tag=customTags.get(which);
                Set<String> ids=new HashSet<>(getSharedPreferences("media_tags",0).getStringSet(tag,new HashSet<>()));
                for(Long id:selected) ids.add(String.valueOf(id));
                getSharedPreferences("media_tags",0).edit().putStringSet(tag,ids).apply();
                selected.clear(); selecting=false; refreshSelectionBar();
                Toast.makeText(this,"已添加到 "+tag,Toast.LENGTH_SHORT).show();
            }).show();
    }

    private ArrayList<Uri> selectedUris() {
        ArrayList<Uri> uris=new ArrayList<>();
        for(MediaItem item:items) if(selected.contains(item.id)) uris.add(item.uri);
        return uris;
    }
    private void changeTrashState(boolean trash) {
        ArrayList<Uri> uris=selectedUris();
        if(uris.isEmpty()) return;
        if(Build.VERSION.SDK_INT>=30) {
            try {
                IntentSenderRequestCompat.launch(this,trash?MediaStore.createTrashRequest(getContentResolver(),uris,true):MediaStore.createTrashRequest(getContentResolver(),uris,false),REQ_TRASH);
            } catch(Exception error) {
                Toast.makeText(this,"无法发起媒体授权："+error.getMessage(),Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this,"此 Android 版本不支持系统最近删除回收箱",Toast.LENGTH_LONG).show();
        }
    }
    private void permanentlyDelete() {
        ArrayList<Uri> uris=selectedUris();
        if(uris.isEmpty()) return;
        if(Build.VERSION.SDK_INT>=30) {
            try { IntentSenderRequestCompat.launch(this,MediaStore.createDeleteRequest(getContentResolver(),uris),REQ_DELETE); }
            catch(Exception error) { Toast.makeText(this,"无法发起删除授权："+error.getMessage(),Toast.LENGTH_LONG).show(); }
        } else Toast.makeText(this,"此 Android 版本不支持此操作",Toast.LENGTH_LONG).show();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_TRASH||requestCode==REQ_DELETE) {
            selected.clear(); selecting=false; refreshSelectionBar(); loadMedia();
        }
    }

    private void openMedia(MediaItem item) {
        try {
            Intent intent=new Intent(this,MediaViewerActivity.class);
            intent.putExtra("media_uri",item.uri.toString());
            intent.putExtra("media_video",item.video);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            overridePendingTransition(0,0);
        } catch(Exception error) {
            Toast.makeText(this,"无法打开媒体文件",Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onBackPressed() {
        if(selecting) {
            selected.clear(); selecting=false; adapter.notifyDataSetChanged(); refreshSelectionBar();
        } else if(!activeTag.equals("全部")) {
            activeTag="全部"; renderTabs(); loadMedia();
        } else super.onBackPressed();
    }

    private static class IntentSenderRequestCompat {
        static void launch(Activity activity,android.app.PendingIntent pendingIntent,int requestCode) throws Exception {
            if(Build.VERSION.SDK_INT>=30) activity.startIntentSenderForResult(
                pendingIntent.getIntentSender(),requestCode,null,0,0,0);
        }
    }

    private class SquareFrame extends FrameLayout {
        SquareFrame() { super(MainActivity.this); }
        @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec) {
            int width=MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY));
        }
    }

    class MediaAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }
        @Override public View getView(int position,View convertView,ViewGroup parent) {
            SquareFrame frame=convertView instanceof SquareFrame?(SquareFrame)convertView:new SquareFrame();
            frame.removeAllViews();
            MediaItem item=items.get(position);
            ImageView image=new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
            try {
                if(Build.VERSION.SDK_INT>=29) image.setImageBitmap(getContentResolver().loadThumbnail(item.uri,new android.util.Size(256,256),null));
                else image.setImageURI(item.uri);
            } catch(Exception error) { image.setBackgroundColor(0xff202020); }
            if(item.video) {
                TextView play=text("▶",12); play.setBackgroundColor(0x55000000);
                frame.addView(play,new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.BOTTOM|Gravity.RIGHT));
            }
            if(selected.contains(item.id)) {
                View shade=new View(MainActivity.this); shade.setBackgroundColor(0x66000000);
                frame.addView(shade,new FrameLayout.LayoutParams(-1,-1));
                TextView check=text("✓",18); frame.addView(check,new FrameLayout.LayoutParams(-1,-1));
            }
            return frame;
        }
    }
}
