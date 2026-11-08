package com.nkjeff.chme261108;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BLACK=Color.BLACK, WHITE=Color.WHITE, MUTED=0xff777777;
    private static final int REQ_MEDIA=40, REQ_DELETE=41;
    private LinearLayout root, toolRow, tabRow, selectionRow;
    private HorizontalScrollView tabScroll;
    private GridView grid;
    private MediaAdapter adapter;
    private final ArrayList<MediaItem> media=new ArrayList<>();
    private final ArrayList<String> customTags=new ArrayList<>();
    private final LinkedHashSet<String> selected=new LinkedHashSet<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ScaleGestureDetector scaleDetector;
    private String activeTag="全部", sortField="添加时间", searchText="";
    private boolean descending=true, selecting=false, dragSelecting=false, pinchInProgress=false;
    private String dragStartedKey=null;
    private float pinchFactor=1f;
    private int columns=4;
    private long lastColumnStepAt=0;
    private long queryGeneration=0;
    private final LinkedHashSet<String> pendingDelete=new LinkedHashSet<>();

    private static final class MediaItem {
        final long id, date, size;
        final boolean video;
        final Uri uri;
        final String key;
        MediaItem(long id,long date,long size,boolean video,Uri uri) {
            this.id=id; this.date=date; this.size=size; this.video=video; this.uri=uri;
            this.key=(video?"v:":"i:")+id;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildInterface();
        readCustomTags();
        drawTabs();
        if(hasMediaAccess()) refreshMedia(); else askMediaAccess();
    }

    private int dp(float n) { return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
    private boolean granted(String permission) {
        return Build.VERSION.SDK_INT<23 || checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;
    }
    private boolean imagesAllowed() {
        if(Build.VERSION.SDK_INT>=34) return granted(Manifest.permission.READ_MEDIA_IMAGES)
            || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        if(Build.VERSION.SDK_INT>=33) return granted(Manifest.permission.READ_MEDIA_IMAGES);
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE);
    }
    private boolean videosAllowed() {
        if(Build.VERSION.SDK_INT>=34) return granted(Manifest.permission.READ_MEDIA_VIDEO)
            || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        if(Build.VERSION.SDK_INT>=33) return granted(Manifest.permission.READ_MEDIA_VIDEO);
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE);
    }
    private boolean hasMediaAccess() { return imagesAllowed() || videosAllowed(); }
    private void askMediaAccess() {
        if(Build.VERSION.SDK_INT>=34) requestPermissions(new String[]{
            Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED},REQ_MEDIA);
        else if(Build.VERSION.SDK_INT>=33) requestPermissions(new String[]{
            Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO},REQ_MEDIA);
        else if(Build.VERSION.SDK_INT>=23) requestPermissions(new String[]{
            Manifest.permission.READ_EXTERNAL_STORAGE},REQ_MEDIA);
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==REQ_MEDIA) refreshMedia();
    }

    private TextView label(String value,int size) {
        TextView t=new TextView(this);
        t.setText(value); t.setTextColor(WHITE); t.setTextSize(size);
        t.setGravity(Gravity.CENTER); t.setIncludeFontPadding(false);
        t.setPadding(dp(5),dp(8),dp(5),dp(8));
        return t;
    }
    private LinearLayout horizontal() {
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
    private void buildInterface() {
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);
        setContentView(root);

        // The Android system status bar is the first persistent row.
        toolRow=horizontal();
        toolRow.setBackgroundColor(BLACK);
        root.addView(toolRow,new LinearLayout.LayoutParams(-1,dp(38)));
        addTool("⟳", "刷新", this::refreshMedia);
        addTool(sortField, "排序字段", this::switchSortField);
        addTool(descending?"↓":"↑", "排序方向", this::switchDirection);

        tabScroll=new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setFillViewport(false);
        tabRow=horizontal();
        tabScroll.addView(tabRow,new HorizontalScrollView.LayoutParams(-2,-1));
        root.addView(tabScroll,new LinearLayout.LayoutParams(-1,dp(52)));

        grid=new GridView(this);
        grid.setBackgroundColor(BLACK);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setNumColumns(columns);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        grid.setPadding(dp(2),dp(2),dp(2),dp(2));
        grid.setClipToPadding(false);
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));

        selectionRow=horizontal();
        selectionRow.setGravity(Gravity.CENTER);
        selectionRow.setVisibility(View.GONE);
        root.addView(selectionRow,new LinearLayout.LayoutParams(-1,dp(48)));

        adapter=new MediaAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent,view,position,id)->{
            if(position<0||position>=media.size()) return;
            MediaItem item=media.get(position);
            if(selecting) toggleSelection(item);
            else openInSystemGallery(item);
        });
        grid.setOnItemLongClickListener((parent,view,position,id)->{
            if(pinchInProgress || position<0 || position>=media.size()) return true;
            selecting=true;
            dragSelecting=true;
            dragStartedKey=media.get(position).key;
            selected.add(dragStartedKey);
            refreshSelectionBar();
            adapter.notifyDataSetChanged();
            return true;
        });
        scaleDetector=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                pinchInProgress=true;
                dragSelecting=false;
                dragStartedKey=null;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                pinchFactor*=detector.getScaleFactor();
                long now=android.os.SystemClock.uptimeMillis();
                if(now-lastColumnStepAt<65) return true;
                // Spread: fewer columns. Pinch: more columns.
                if(pinchFactor>=1.045f) {
                    changeColumns(-1); pinchFactor=1f; lastColumnStepAt=now;
                } else if(pinchFactor<=0.955f) {
                    changeColumns(1); pinchFactor=1f; lastColumnStepAt=now;
                }
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                pinchInProgress=false;
                pinchFactor=1f;
            }
        });
        grid.setOnTouchListener((v,event)->{
            scaleDetector.onTouchEvent(event);
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_POINTER_DOWN) {
                pinchInProgress=true;
                if(dragSelecting && dragStartedKey!=null) {
                    selected.remove(dragStartedKey);
                    dragStartedKey=null;
                    dragSelecting=false;
                    adapter.notifyDataSetChanged();
                    refreshSelectionBar();
                }
                return true;
            }
            if(event.getPointerCount()>1 || pinchInProgress) {
                if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                    pinchInProgress=false;
                    dragSelecting=false;
                }
                return true;
            }
            if(action==MotionEvent.ACTION_MOVE && selecting && dragSelecting) {
                int p=grid.pointToPosition((int)event.getX(),(int)event.getY());
                if(p>=0&&p<media.size()&&selected.add(media.get(p).key)) {
                    adapter.notifyDataSetChanged();
                    refreshSelectionBar();
                }
            }
            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                dragSelecting=false;
                dragStartedKey=null;
            }
            return false;
        });
    }

    private void addTool(String initial,String description,Runnable action) {
        TextView t=label(initial,16);
        t.setContentDescription(description);
        toolRow.addView(t,new LinearLayout.LayoutParams(0,-1,1f));
        t.setOnClickListener(v->action.run());
    }
    private void updateToolText() {
        ((TextView)toolRow.getChildAt(0)).setText("⟳");
        ((TextView)toolRow.getChildAt(1)).setText(sortField);
        ((TextView)toolRow.getChildAt(2)).setText(descending?"↓":"↑");
        ((TextView)toolRow.getChildAt(1)).setTextSize(14);
    }
    private void switchSortField() {
        sortField=sortField.equals("添加时间")?"媒体大小":"添加时间";
        updateToolText(); refreshMedia();
    }
    private void switchDirection() { descending=!descending; updateToolText(); refreshMedia(); }
    private void changeColumns(int delta) {
        int next=Math.max(4,Math.min(16,columns+delta));
        if(next==columns) return;
        columns=next;
        grid.animate().cancel();
        grid.animate().alpha(0.72f).setDuration(55).withEndAction(()->{
            grid.setNumColumns(columns);
            adapter.notifyDataSetChanged();
            grid.animate().alpha(1f).setDuration(85).start();
        }).start();
    }

    private void readCustomTags() {
        Set<String> saved=getSharedPreferences("tag_store",0).getStringSet("custom",new HashSet<>());
        customTags.clear(); customTags.addAll(new LinkedHashSet<>(saved));
    }
    private void saveCustomTags() {
        getSharedPreferences("tag_store",0).edit().putStringSet("custom",new HashSet<>(customTags)).apply();
    }
    private void drawTabs() {
        tabRow.removeAllViews();
        if(activeTag.equals("最近删除")) {
            addTab("最近删除");
        } else {
            addTab("全部"); addTab("图片"); addTab("视频"); addTab("最近删除");
            for(String tag:customTags) addTab(tag);
            TextView add=label("＋",20);
            tabRow.addView(add,new LinearLayout.LayoutParams(dp(48),-1));
            add.setOnClickListener(v->addCustomTag());
        }
    }
    private void addTab(String name) {
        TextView t=label(name,14);
        t.setTextColor(activeTag.equals(name)?WHITE:MUTED);
        if(activeTag.equals(name)) t.setTypeface(null,Typeface.BOLD);
        tabRow.addView(t,new LinearLayout.LayoutParams(-2,-1));
        t.setOnClickListener(v->{
            if(!activeTag.equals(name)) {
                activeTag=name; clearSelection(); drawTabs(); refreshMedia();
            }
        });
        if(customTags.contains(name)) t.setOnLongClickListener(v->{
            new AlertDialog.Builder(this).setMessage("删除标签“"+name+"”？媒体文件不会被删除。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
                    customTags.remove(name); saveCustomTags();
                    getSharedPreferences("tag_membership",0).edit().remove(name).apply();
                    if(activeTag.equals(name)) activeTag="全部";
                    drawTabs(); refreshMedia();
                }).show();
            return true;
        });
    }
    private void addCustomTag() {
        EditText input=new EditText(this);
        input.setSingleLine(true); input.setTextColor(WHITE); input.setHintTextColor(MUTED);
        input.setHint("标签名称");
        new AlertDialog.Builder(this).setTitle("新增标签").setView(input)
            .setNegativeButton("取消",null).setPositiveButton("添加",(d,w)->{
                String name=input.getText().toString().trim();
                if(name.isEmpty()||Arrays.asList("全部","图片","视频","最近删除").contains(name)||customTags.contains(name)) {
                    Toast.makeText(this,"标签名称无效或已存在",Toast.LENGTH_SHORT).show(); return;
                }
                customTags.add(name); saveCustomTags(); activeTag=name; drawTabs(); refreshMedia();
            }).show();
    }

    private void refreshMedia() {
        final long generation=++queryGeneration;
        final String tag=activeTag, query=searchText, field=sortField;
        final boolean desc=descending, allowImages=imagesAllowed(), allowVideos=videosAllowed();
        worker.execute(()->{
            ArrayList<MediaItem> found=new ArrayList<>();
            if(!allowImages&&!allowVideos) {
                runOnUiThread(()->{if(generation==queryGeneration){media.clear();adapter.notifyDataSetChanged();}});
                return;
            }
            Uri files=MediaStore.Files.getContentUri("external");
            String[] projection={MediaStore.Files.FileColumns._ID,MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.SIZE,MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.DISPLAY_NAME};
            StringBuilder where=new StringBuilder("(");
            ArrayList<String> args=new ArrayList<>();
            if(allowImages) { where.append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?"); args.add("1"); }
            if(allowImages&&allowVideos) where.append(" OR ");
            if(allowVideos) { where.append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?"); args.add("3"); }
            where.append(")");
            if(tag.equals("图片")) where.append(" AND ").append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=1");
            if(tag.equals("视频")) where.append(" AND ").append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=3");
            if(Build.VERSION.SDK_INT>=30) where.append(" AND ").append(MediaStore.MediaColumns.IS_TRASHED).append("=0");
            if(!query.isEmpty()) { where.append(" AND ").append(MediaStore.Files.FileColumns.DISPLAY_NAME).append(" LIKE ?"); args.add("%"+query+"%"); }
            Set<String> trash=new HashSet<>(getSharedPreferences("app_trash",0).getStringSet("keys",new HashSet<>()));
            Set<String> members=new HashSet<>(getSharedPreferences("tag_membership",0).getStringSet(tag,new HashSet<>()));
            String order=(field.equals("媒体大小")?MediaStore.Files.FileColumns.SIZE:MediaStore.Files.FileColumns.DATE_ADDED)+(desc?" DESC":" ASC");
            try(Cursor c=getContentResolver().query(files,projection,where.toString(),args.toArray(new String[0]),order)) {
                if(c!=null) while(c.moveToNext()) {
                    long id=c.getLong(0), date=c.getLong(1), size=c.getLong(2);
                    int type=c.getInt(3);
                    boolean video=type==MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                    Uri uri=Uri.withAppendedPath(video?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI,String.valueOf(id));
                    MediaItem item=new MediaItem(id,date,size,video,uri);
                    boolean isTrash=trash.contains(item.key);
                    if(tag.equals("最近删除")!=isTrash) continue;
                    if(!tag.equals("全部")&&!tag.equals("图片")&&!tag.equals("视频")&&!tag.equals("最近删除")&&!members.contains(item.key)) continue;
                    found.add(item);
                }
            } catch(Exception e) { android.util.Log.e("chme","MediaStore query failed",e); }
            runOnUiThread(()->{
                if(generation!=queryGeneration) return;
                media.clear(); media.addAll(found);
                selected.retainAll(currentKeys());
                adapter.notifyDataSetChanged(); refreshSelectionBar();
            });
        });
    }
    private Set<String> currentKeys() {
        HashSet<String> keys=new HashSet<>();
        for(MediaItem item:media) keys.add(item.key);
        return keys;
    }

    private void toggleSelection(MediaItem item) {
        if(!selected.add(item.key)) selected.remove(item.key);
        if(selected.isEmpty()) selecting=false;
        adapter.notifyDataSetChanged(); refreshSelectionBar();
    }
    private void clearSelection() {
        selected.clear(); selecting=false; dragSelecting=false;
        refreshSelectionBar();
    }
    private void refreshSelectionBar() {
        selectionRow.removeAllViews();
        selectionRow.setVisibility(selecting?View.VISIBLE:View.GONE);
        if(!selecting) return;
        TextView count=label(selected.size()+" 已选",13); selectionRow.addView(count);
        if(activeTag.equals("最近删除")) {
            TextView restore=label("恢复",13); selectionRow.addView(restore);
            restore.setOnClickListener(v->updateTrash(false));
            TextView delete=label("永久删除",13); selectionRow.addView(delete);
            delete.setOnClickListener(v->permanentlyDelete());
        } else {
            if(!customTags.isEmpty()) {
                TextView tag=label("加入标签",13); selectionRow.addView(tag);
                tag.setOnClickListener(v->addSelectionToTag());
            }
            TextView trash=label("最近删除",13); selectionRow.addView(trash);
            trash.setOnClickListener(v->updateTrash(true));
        }
        TextView cancel=label("取消",13); selectionRow.addView(cancel);
        cancel.setOnClickListener(v->{clearSelection();adapter.notifyDataSetChanged();});
    }
    private void addSelectionToTag() {
        new AlertDialog.Builder(this).setTitle("添加到标签")
            .setItems(customTags.toArray(new String[0]),(d,index)->{
                String tag=customTags.get(index);
                Set<String> members=new HashSet<>(getSharedPreferences("tag_membership",0).getStringSet(tag,new HashSet<>()));
                members.addAll(selected);
                getSharedPreferences("tag_membership",0).edit().putStringSet(tag,members).apply();
                clearSelection(); Toast.makeText(this,"已加入 "+tag,Toast.LENGTH_SHORT).show();
            }).show();
    }
    private void updateTrash(boolean value) {
        Set<String> keys=new HashSet<>(getSharedPreferences("app_trash",0).getStringSet("keys",new HashSet<>()));
        if(value) keys.addAll(selected); else keys.removeAll(selected);
        getSharedPreferences("app_trash",0).edit().putStringSet("keys",keys).apply();
        clearSelection(); refreshMedia();
    }
    private void permanentlyDelete() {
        ArrayList<Uri> uris=new ArrayList<>();
        for(MediaItem item:media) if(selected.contains(item.key)) uris.add(item.uri);
        if(uris.isEmpty()) return;
        if(Build.VERSION.SDK_INT>=30) {
            try {
                pendingDelete.clear(); pendingDelete.addAll(selected);
                startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(),uris).getIntentSender(),
                    REQ_DELETE,null,0,0,0);
            } catch(Exception e) { Toast.makeText(this,"无法请求系统删除授权",Toast.LENGTH_SHORT).show(); }
        } else Toast.makeText(this,"当前 Android 版本暂不支持此删除流程",Toast.LENGTH_SHORT).show();
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_DELETE) {
            if(resultCode==RESULT_OK) {
                Set<String> trash=new HashSet<>(getSharedPreferences("app_trash",0).getStringSet("keys",new HashSet<>()));
                trash.removeAll(pendingDelete);
                getSharedPreferences("app_trash",0).edit().putStringSet("keys",trash).apply();
                for(String tag:customTags) {
                    Set<String> members=new HashSet<>(getSharedPreferences("tag_membership",0).getStringSet(tag,new HashSet<>()));
                    members.removeAll(pendingDelete);
                    getSharedPreferences("tag_membership",0).edit().putStringSet(tag,members).apply();
                }
            }
            pendingDelete.clear(); clearSelection(); refreshMedia();
        }
    }

    private void openInSystemGallery(MediaItem item) {
        try {
            String mime=item.video?"video/*":"image/*";
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(item.uri,mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch(Exception e) {
            Toast.makeText(this,"系统没有可打开此媒体的相册或查看器",Toast.LENGTH_LONG).show();
        }
    }

    private class MediaAdapter extends BaseAdapter {
        @Override public int getCount(){return media.size();}
        @Override public Object getItem(int p){return media.get(p);}
        @Override public long getItemId(int p){return media.get(p).id;}
        @Override public View getView(int position,View recycled,ViewGroup parent) {
            FrameLayout cell=recycled instanceof FrameLayout?(FrameLayout)recycled:new FrameLayout(MainActivity.this);
            cell.removeAllViews();
            MediaItem item=media.get(position);
            ImageView thumb=new ImageView(MainActivity.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(thumb,new FrameLayout.LayoutParams(-1,-1));
            try {
                if(Build.VERSION.SDK_INT>=29) thumb.setImageBitmap(getContentResolver().loadThumbnail(item.uri,new Size(240,240),null));
                else thumb.setImageURI(item.uri);
            } catch(Exception e) { thumb.setBackgroundColor(0xff171717); }
            if(item.video) {
                TextView marker=label("▶",10); marker.setBackgroundColor(0x66000000);
                cell.addView(marker,new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.RIGHT|Gravity.BOTTOM));
            }
            if(selected.contains(item.key)) {
                View shade=new View(MainActivity.this); shade.setBackgroundColor(0x66000000);
                cell.addView(shade,new FrameLayout.LayoutParams(-1,-1));
                TextView check=label("✓",18); cell.addView(check,new FrameLayout.LayoutParams(-1,-1));
            }
            int width=getResources().getDisplayMetrics().widthPixels;
            int cellWidth=(width-dp(4)-(columns-1)*dp(2))/columns;
            cell.setLayoutParams(new AbsListView.LayoutParams(-1,cellWidth));
            return cell;
        }
    }

    @Override public void onBackPressed() {
        if(selecting) { clearSelection(); adapter.notifyDataSetChanged(); }
        else if(!activeTag.equals("全部")) { activeTag="全部"; drawTabs(); refreshMedia(); }
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
