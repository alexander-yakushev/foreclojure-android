package org.bytopia.foreclojure;

import android.widget.TextView;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.view.MotionEvent;

public class SafeLinkMethod extends LinkMovementMethod {

    private static SafeLinkMethod instance;

    private SafeLinkMethod() { }

    public static SafeLinkMethod getInstance() {
        if (instance == null) {
            instance = new SafeLinkMethod();
        }
        return instance;
    }

    @Override
    public boolean onTouchEvent(TextView widget, Spannable buffer,
                                MotionEvent event) {
        try {
            return super.onTouchEvent( widget, buffer, event ) ;
        } catch( Exception ex ) {
            return true;
        }
    }

}
