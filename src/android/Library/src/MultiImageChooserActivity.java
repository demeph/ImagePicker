/*
 * Copyright (c) 2012, David Erosa
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following  conditions are met:
 *
 *   Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 *   Redistributions in binary form must reproduce the above copyright notice,
 *      this list of conditions and the following  disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,  BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT  SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR  BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDIN G NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH  DAMAGE
 *
 * Code modified by Andrew Stephan for Sync OnSet
 *
 */

package com.synconset;

import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.synconset.FakeR;
import android.app.AlertDialog;
import android.app.LoaderManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.ActionBar;
import androidx.core.view.WindowCompat;

import android.util.Base64;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;

public class MultiImageChooserActivity extends AppCompatActivity implements
        OnItemClickListener,
        LoaderManager.LoaderCallbacks<Cursor> {

    private static final String TAG = "ImagePicker";

    public static final int NOLIMIT = -1;
    public static final String MAX_IMAGES_KEY = "MAX_IMAGES";
    public static final String WIDTH_KEY = "WIDTH";
    public static final String HEIGHT_KEY = "HEIGHT";
    public static final String QUALITY_KEY = "QUALITY";
    public static final String OUTPUT_TYPE_KEY = "OUTPUT_TYPE";

    private ImageAdapter ia;

    private Cursor imagecursor, actualimagecursor;
    private int image_column_index, image_column_orientation, actual_image_column_index, orientation_column_index;
    private int colWidth;

    private static final int CURSORLOADER_THUMBS = 0;
    private static final int CURSORLOADER_REAL = 1;

    private Map<String, Integer> fileNames = new HashMap<String, Integer>();

    private SparseBooleanArray checkStatus = new SparseBooleanArray();

    private int maxImages;
    private int maxImageCount;

    private int desiredWidth;
    private int desiredHeight;
    private int quality;
    private OutputType outputType;

    private final ImageFetcher fetcher = new ImageFetcher();

    private int selectedColor = 0xff32b2e1;
    private boolean shouldRequestThumb = true;

    private FakeR fakeR;
    private View abDoneView;
    private View abDiscardView;

    private ProgressDialog progress;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fakeR = new FakeR(this);
        setContentView(fakeR.getId("layout", "multiselectorgrid"));
        fileNames.clear();

        maxImages = getIntent().getIntExtra(MAX_IMAGES_KEY, NOLIMIT);
        desiredWidth = getIntent().getIntExtra(WIDTH_KEY, 0);
        desiredHeight = getIntent().getIntExtra(HEIGHT_KEY, 0);
        quality = getIntent().getIntExtra(QUALITY_KEY, 0);
        maxImageCount = maxImages;
        outputType = OutputType.fromValue(getIntent().getIntExtra(OUTPUT_TYPE_KEY, 0));

        Display display = getWindowManager().getDefaultDisplay();
        int width = display.getWidth();

        colWidth = width / 4;

        GridView gridView = (GridView) findViewById(fakeR.getId("id", "gridview"));
        gridView.setOnItemClickListener(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            gridView.setPadding(0, 200 ,0, 0);
        }
        gridView.setOnScrollListener(new OnScrollListener() {
            private int lastFirstItem = 0;
            private long timestamp = System.currentTimeMillis();

            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_IDLE) {
                    shouldRequestThumb = true;
                    ia.notifyDataSetChanged();
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                float dt = System.currentTimeMillis() - timestamp;
                if (firstVisibleItem != lastFirstItem) {
                    double speed = 1 / dt * 1000;
                    lastFirstItem = firstVisibleItem;
                    timestamp = System.currentTimeMillis();

                    // Limit if we go faster than a page a second
                    shouldRequestThumb = speed < visibleItemCount;
                }
            }
        });

        ia = new ImageAdapter();
        gridView.setAdapter(ia);

        LoaderManager.enableDebugLogging(false);
        getLoaderManager().initLoader(CURSORLOADER_THUMBS, null, this);
        getLoaderManager().initLoader(CURSORLOADER_REAL, null, this);
        setupHeader();
        updateAcceptButton();
        progress = new ProgressDialog(this);
        progress.setTitle(getString(fakeR.getId("string", "multi_image_picker_processing_images_title")));
        progress.setMessage(getString(fakeR.getId("string", "multi_image_picker_processing_images_message")));
    }

    @Override
    public void onItemClick(AdapterView<?> arg0, View view, int position, long id) {
        String name = getImageName(position);
        int rotation = getImageRotation(position);

        if (name == null) {
            return;
        }

        boolean isChecked = !isChecked(position);

        if (maxImages == 0 && isChecked) {
            isChecked = false;
            new AlertDialog.Builder(this)
                    .setTitle(String.format(getString(fakeR.getId("string", "max_count_photos_title")), maxImageCount))
                    .setMessage(String.format(getString(fakeR.getId("string", "max_count_photos_message")), maxImageCount))
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                        }
                    })
                    .create()
                    .show();

        } else if (isChecked) {
            fileNames.put(name, rotation);

            if (maxImageCount == 1) {
                selectClicked();

            } else {
                maxImages--;
                ImageView imageView = (ImageView) view;

                if (android.os.Build.VERSION.SDK_INT >= 16) {
                    imageView.setImageAlpha(128);
                } else {
                    imageView.setAlpha(128);
                }

                view.setBackgroundColor(selectedColor);
            }
        } else {
            fileNames.remove(name);
            maxImages++;
            ImageView imageView = (ImageView) view;

            if (android.os.Build.VERSION.SDK_INT >= 16) {
                imageView.setImageAlpha(255);
            } else {
                imageView.setAlpha(255);
            }

            view.setBackgroundColor(Color.TRANSPARENT);
        }

        checkStatus.put(position, isChecked);
        updateAcceptButton();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int cursorID, Bundle arg1) {
        ArrayList<String> img = new ArrayList<String>();
        switch (cursorID) {
            case CURSORLOADER_THUMBS:
                img.add(MediaStore.Images.Media._ID);
                img.add(MediaStore.Images.Media.ORIENTATION);
                break;

            case CURSORLOADER_REAL:
                img.add(MediaStore.Images.Thumbnails.DATA);
                img.add(MediaStore.Images.Media.ORIENTATION);
                break;
        }

        return new CursorLoader(
                this,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                img.toArray(new String[img.size()]),
                null,
                null,
                "DATE_MODIFIED DESC"
        );
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        if (cursor == null) {
            // NULL cursor. This usually means there's no image database yet....
            return;
        }

        switch (loader.getId()) {
            case CURSORLOADER_THUMBS:
                imagecursor = cursor;
                image_column_index = imagecursor.getColumnIndex(MediaStore.Images.Media._ID);
                image_column_orientation = imagecursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION);
                ia.notifyDataSetChanged();
                break;

            case CURSORLOADER_REAL:
                actualimagecursor = cursor;
                actual_image_column_index = actualimagecursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                orientation_column_index = actualimagecursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION);
                break;
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        switch (loader.getId()) {
            case CURSORLOADER_THUMBS:
                imagecursor = null;
                break;

            case CURSORLOADER_REAL:
                actualimagecursor = null;
                break;
        }
    }

    public void cancelClicked() {
        setResult(RESULT_CANCELED);
        finish();
    }

    public void selectClicked() {
        abDiscardView.setEnabled(false);
        abDoneView.setEnabled(false);
        progress.show();

        if (fileNames.isEmpty()) {
            setResult(RESULT_CANCELED);
            progress.dismiss();
            finish();
        } else {
            setRequestedOrientation(getResources().getConfiguration().orientation); //prevent orientation changes during processing
            new ResizeImagesTask().execute(fileNames.entrySet());
        }
    }


    /*********************
     * Helper Methods
     ********************/
    private void updateAcceptButton() {
        if (abDoneView != null) {
            abDoneView.setEnabled(fileNames.size() != 0);
        }
    }

    private void setupHeader() {
        // From Roman Nkk's code
        // https://plus.google.com/113735310430199015092/posts/R49wVvcDoEW
        // Inflate a "Done/Discard" custom action bar view
        /*
         * Copyright 2013 The Android Open Source Project
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
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View customActionBarView = inflater.inflate(
                fakeR.getId("layout", "actionbar_custom_view_done_discard"),
                null
        );

        abDoneView = customActionBarView.findViewById(fakeR.getId("id", "actionbar_done"));
        abDoneView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // "Done"
                selectClicked();
            }
        });

        abDiscardView = customActionBarView.findViewById(fakeR.getId("id", "actionbar_discard"));
        abDiscardView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelClicked();
            }
        });

        // Show the custom action bar view and hide the normal Home icon and title.
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayOptions(
                    ActionBar.DISPLAY_SHOW_CUSTOM,
                    ActionBar.DISPLAY_SHOW_CUSTOM
                            | ActionBar.DISPLAY_SHOW_HOME
                            | ActionBar.DISPLAY_SHOW_TITLE
            );
            actionBar.setCustomView(customActionBarView, new ActionBar.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }
    }

    private String getImageName(int position) {
        actualimagecursor.moveToPosition(position);
        String name = null;

        try {
            name = actualimagecursor.getString(actual_image_column_index);
        } catch (Exception e) {
            // Do something?
        }

        return name;
    }

    private int getImageRotation(int position) {
        actualimagecursor.moveToPosition(position);
        int rotation = 0;

        try {
            rotation = actualimagecursor.getInt(orientation_column_index);
        } catch (Exception e) {
            // Do something?
        }

        return rotation;
    }

    public boolean isChecked(int position) {
        return checkStatus.get(position);
    }


    /*********************
     * Nested Classes
     ********************/
    private class SquareImageView extends ImageView {
        public SquareImageView(Context context) {
            super(context);
        }

        @Override
        public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }


    private class ImageAdapter extends BaseAdapter {

        public int getCount() {
            if (imagecursor != null) {
                return imagecursor.getCount();
            } else {
                return 0;
            }
        }

        public Object getItem(int position) {
            return position;
        }

        public long getItemId(int position) {
            return position;
        }

        // create a new ImageView for each item referenced by the Adapter
        public View getView(int position, View convertView, ViewGroup parent) {

            if (convertView == null) {
                ImageView temp = new SquareImageView(MultiImageChooserActivity.this);
                temp.setScaleType(ImageView.ScaleType.CENTER_CROP);
                convertView = temp;
            }

            ImageView imageView = (ImageView) convertView;
            imageView.setImageBitmap(null);

            if (!imagecursor.moveToPosition(position)) {
                return imageView;
            }

            if (image_column_index == -1) {
                return imageView;
            }

            final int id = imagecursor.getInt(image_column_index);
            final int rotate = imagecursor.getInt(image_column_orientation);

            if (isChecked(position)) {
                if (android.os.Build.VERSION.SDK_INT >= 16) {
                    imageView.setImageAlpha(128);
                } else {
                    imageView.setAlpha(128);
                }

                imageView.setBackgroundColor(selectedColor);

            } else {
                if (android.os.Build.VERSION.SDK_INT >= 16) {
                    imageView.setImageAlpha(255);
                } else {
                    imageView.setAlpha(255);
                }
                imageView.setBackgroundColor(Color.TRANSPARENT);
            }

            if (shouldRequestThumb) {
                fetcher.fetch(id, imageView, colWidth, rotate);
            }

            return imageView;
        }
    }

    private class ResizeImagesTask extends AsyncTask<Set<Entry<String, Integer>>, Void, ArrayList<String>> {
        private Exception asyncTaskError = null;

        @Override
        protected ArrayList<String> doInBackground(Set<Entry<String, Integer>>... fileSets) {
            Set<Entry<String, Integer>> fileNames = fileSets[0];
            ArrayList<String> al = new ArrayList<String>();
            try {
                Iterator<Entry<String, Integer>> i = fileNames.iterator();
                Bitmap bmp;
                while (i.hasNext()) {
                    Entry<String, Integer> imageInfo = i.next();
                    File file = new File(imageInfo.getKey());
                    int rotate = imageInfo.getValue();
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 1;
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                    int width = options.outWidth;
                    int height = options.outHeight;
                    float scale = calculateScale(width, height);

                    if (scale < 1) {
                        int finalWidth = (int)(width * scale);
                        int finalHeight = (int)(height * scale);
                        int inSampleSize = calculateInSampleSize(options, finalWidth, finalHeight);
                        options = new BitmapFactory.Options();
                        options.inSampleSize = inSampleSize;

                        try {
                            bmp = this.tryToGetBitmap(file, options, rotate, true);
                        } catch (OutOfMemoryError e) {
                            options.inSampleSize = calculateNextSampleSize(options.inSampleSize);
                            try {
                                bmp = this.tryToGetBitmap(file, options, rotate, false);
                            } catch (OutOfMemoryError e2) {
                                throw new IOException("Unable to load image into memory.");
                            }
                        }
                    } else {
                        try {
                            bmp = this.tryToGetBitmap(file, null, rotate, false);
                        } catch(OutOfMemoryError e) {
                            options = new BitmapFactory.Options();
                            options.inSampleSize = 2;

                            try {
                                bmp = this.tryToGetBitmap(file, options, rotate, false);
                            } catch(OutOfMemoryError e2) {
                                options = new BitmapFactory.Options();
                                options.inSampleSize = 4;

                                try {
                                    bmp = this.tryToGetBitmap(file, options, rotate, false);
                                } catch (OutOfMemoryError e3) {
                                    throw new IOException("Unable to load image into memory.");
                                }
                            }
                        }
                    }

                    if (outputType == OutputType.FILE_URI) {
                        file = storeImage(bmp, file.getName());
                        al.add(Uri.fromFile(file).toString());

                    } else if (outputType == OutputType.BASE64_STRING) {
                        al.add(getBase64OfImage(bmp));
                    }
                }
                return al;
            } catch (IOException e) {
                try {
                    asyncTaskError = e;
                    for (int i = 0; i < al.size(); i++) {
                        URI uri = new URI(al.get(i));
                        File file = new File(uri);
                        file.delete();
                    }
                } catch (Exception ignore) {
                }

                return new ArrayList<String>();
            }
        }

        @Override
        protected void onPostExecute(ArrayList<String> al) {
            Intent data = new Intent();

            if (asyncTaskError != null) {
                Bundle res = new Bundle();
                res.putString("ERRORMESSAGE", asyncTaskError.getMessage());
                data.putExtras(res);
                setResult(RESULT_CANCELED, data);

            } else if (al.size() > 0) {
                Bundle res = new Bundle();
                res.putStringArrayList("MULTIPLEFILENAMES", al);

                if (imagecursor != null) {
                    res.putInt("TOTALFILES", imagecursor.getCount());
                }

                int sync = ResultIPC.get().setLargeData(res);
                data.putExtra("bigdata:synccode", sync);
                setResult(RESULT_OK, data);

            } else {
                setResult(RESULT_CANCELED, data);
            }

            progress.dismiss();
            finish();
        }

        private Bitmap tryToGetBitmap(File file,
                                      BitmapFactory.Options options,
                                      int rotate,
                                      boolean shouldScale) throws IOException, OutOfMemoryError {
            Bitmap bmp;
            if (options == null) {
                bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
            } else {
                bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            }

            if (bmp == null) {
                throw new IOException("The image file could not be opened.");
            }

            if (options != null && shouldScale) {
                float scale = calculateScale(options.outWidth, options.outHeight);
                bmp = this.getResizedBitmap(bmp, scale);
            }

            if (rotate != 0) {
                Matrix matrix = new Matrix();
                matrix.setRotate(rotate);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            }

            return bmp;
        }

        /*
         * The following functions are originally from
         * https://github.com/raananw/PhoneGap-Image-Resizer
         *
         * They have been modified by Andrew Stephan for Sync OnSet
         *
         * The software is open source, MIT Licensed.
         * Copyright (C) 2012, webXells GmbH All Rights Reserved.
         */
        private File storeImage(Bitmap bmp, String fileName) throws IOException {
            int index = fileName.lastIndexOf('.');
            String name = fileName.substring(0, index);
            String ext = fileName.substring(index);
            File file = File.createTempFile("tmp_" + name, ext);
            OutputStream outStream = new FileOutputStream(file);

            if (ext.compareToIgnoreCase(".png") == 0) {
                bmp.compress(Bitmap.CompressFormat.PNG, quality, outStream);
            } else {
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, outStream);
            }

            outStream.flush();
            outStream.close();
            return file;
        }

        private Bitmap getResizedBitmap(Bitmap bm, float factor) {
            int width = bm.getWidth();
            int height = bm.getHeight();
            // create a matrix for the manipulation
            Matrix matrix = new Matrix();
            // resize the bit map
            matrix.postScale(factor, factor);
            // recreate the new Bitmap
            return Bitmap.createBitmap(bm, 0, 0, width, height, matrix, false);
        }

        private String getBase64OfImage(Bitmap bm) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            return Base64.encodeToString(byteArray, Base64.NO_WRAP);
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) > reqHeight && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    private int calculateNextSampleSize(int sampleSize) {
        double logBaseTwo = (int)(Math.log(sampleSize) / Math.log(2));
        return (int)Math.pow(logBaseTwo + 1, 2);
    }

    private float calculateScale(int width, int height) {
        float widthScale = 1.0f;
        float heightScale = 1.0f;
        float scale = 1.0f;
        if (desiredWidth > 0 || desiredHeight > 0) {
            if (desiredHeight == 0 && desiredWidth < width) {
                scale = (float)desiredWidth/width;

            } else if (desiredWidth == 0 && desiredHeight < height) {
                scale = (float)desiredHeight/height;

            } else {
                if (desiredWidth > 0 && desiredWidth < width) {
                    widthScale = (float)desiredWidth/width;
                }

                if (desiredHeight > 0 && desiredHeight < height) {
                    heightScale = (float)desiredHeight/height;
                }

                if (widthScale < heightScale) {
                    scale = widthScale;
                } else {
                    scale = heightScale;
                }
            }
        }

        return scale;
    }

    enum OutputType {

        FILE_URI(0), BASE64_STRING(1);

        int value;

        OutputType(int value) {
            this.value = value;
        }

        public static OutputType fromValue(int value) {
            for (OutputType type : OutputType.values()) {
                if (type.value == value) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid enum value specified");
        }
    }
}
