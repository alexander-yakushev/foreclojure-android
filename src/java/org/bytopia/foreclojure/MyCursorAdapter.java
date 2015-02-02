package org.bytopia.foreclojure;

import android.widget.BaseAdapter;
import android.widget.CursorAdapter;
import android.database.Cursor;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import clojure.lang.IFn;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

public class MyCursorAdapter extends CursorAdapter {

    private IFn newViewFn;
    private IFn bindViewFn;
    private IFn getViewFn;

    public MyCursorAdapter(Context context, Cursor cursor, IFn newViewFn, IFn bindViewFn, IFn getViewFn) {
        super(context, cursor);
        this.newViewFn = newViewFn;
        this.bindViewFn = bindViewFn;
        this.getViewFn = getViewFn;
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return (View)newViewFn.invoke(context, cursor, parent);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        bindViewFn.invoke(view, context, cursor);
    }

    @Override
    public Object getItem(int position) {
        return getViewFn.invoke(position, super.getItem(position));
    }

}
