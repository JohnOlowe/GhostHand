package damjay.control.ghosthand.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;

/**
 * The "type or paste, edit, then send" box, shared by host and guest.
 *
 * <p>A dialog rather than an inline field for one concrete reason: the guest is
 * full-screen and immersive, so an EditText welded to the bottom of the video
 * would sit underneath the soft keyboard (decorFitsSystemWindows is off). A
 * dialog owns its own window, so paste, selection handles, autocorrect and the
 * keyboard all behave natively on every API level this app supports, including
 * KitKat.
 *
 * <p>Draft rules: opening prefills from the local clipboard (the common case is
 * "edit what I copied before sending it over"); an <em>unedited</em> prefill is
 * not remembered, but any real edit survives closing the dialog, so a half-
 * finished message is still there next time.
 */
public final class TextComposer {

    /** What "Send" does with the text: push it to the peer's clipboard. */
    public interface Send {
        void onSend(String text);
    }

    private AlertDialog dialog;
    private EditText input;
    private boolean sent;
    private String draft = "";
    private String baseline = "";

    /** True while the dialog is on screen. */
    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    /** Opens (or re-opens) the composer. Call from the UI thread. */
    public void open(Activity host, Send action) {
        if (isShowing()) {
            dialog.show();
            return;
        }
        input = new EditText(host);
        input.setHint(damjay.control.ghosthand.R.string.compose_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setSingleLine(false);
        String prefill = !draft.isEmpty() ? draft : readClipboard(host);
        input.setText(prefill);
        input.setSelection(input.getText().length());
        baseline = prefill;
        sent = false;

        int pad = (int) (20 * host.getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(host);
        wrap.setPadding(pad, pad / 2, pad, 0);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrap.addView(input, lp);

        AlertDialog d = new AlertDialog.Builder(host)
                .setTitle(damjay.control.ghosthand.R.string.compose_title)
                .setView(wrap)
                .setPositiveButton(damjay.control.ghosthand.R.string.compose_send, (dlg, w) -> {
                    sent = true;
                    draft = "";
                    String text = input.getText().toString();
                    action.onSend(text);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        d.setOnDismissListener(dlg -> {
            if (!sent) {
                String now = input.getText().toString();
                // Remember edits only: an untouched clipboard prefill must not
                // become a stale draft that shadows the NEXT copied text.
                draft = now.equals(baseline) ? "" : now;
            }
            dialog = null;
            input = null;
        });
        d.show();

        // Empty text must never be sent (it would read as a failure on the peer).
        Button positive = d.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setEnabled(!prefill.trim().isEmpty());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                positive.setEnabled(e.toString().trim().length() > 0);
            }
        });
        dialog = d;
    }

    /** Fills the open composer with text that just arrived from the peer. */
    public void setIncoming(String text) {
        if (isShowing()) {
            input.setText(text);
            input.setSelection(text.length());
            baseline = text;
        }
    }

    /** Current clipboard text, or "" - never throws (background reads may fail). */
    public static String readClipboard(Context ctx) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = cm == null ? null : cm.getPrimaryClip();
            if (data != null && data.getItemCount() > 0) {
                CharSequence text = data.getItemAt(0).coerceToText(ctx);
                if (text != null) {
                    return text.toString();
                }
            }
        } catch (RuntimeException e) {
            // Android 10+ may refuse a background read; the dialog just opens empty.
        }
        return "";
    }
}
