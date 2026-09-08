package com.example.charge_to_an_account;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.List;

/** 分类管理：增删、上下移排序；内置分类不可删，被流水引用的分类不可删 */
public class CategoryManageActivity extends Activity {

    private RecordDbHelper db;
    private LinearLayout list;
    private EditText etNew;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_category_manage);
        // 水滴屏/刘海屏避让
        android.view.View root = findViewById(R.id.root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        db = RecordDbHelper.get(this);
        db.ensureCategoriesSeeded();

        list = findViewById(R.id.listCategories);
        etNew = findViewById(R.id.etNewCategory);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAddCategory).setOnClickListener(v -> addCategory());

        rebuild();
    }

    private void rebuild() {
        list.removeAllViews();
        List<String> cats = db.categories();
        int red = ContextCompat.getColor(this, R.color.seal_red);
        int soft = ContextCompat.getColor(this, R.color.ink_soft);
        int faint = ContextCompat.getColor(this, R.color.ink_faint);

        for (int i = 0; i < cats.size(); i++) {
            String name = cats.get(i);
            boolean builtin = db.isBuiltinCategory(name);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            if (i > 0) row.setBackgroundResource(R.drawable.dotted_leader);

            TextView nameTv = new TextView(this);
            nameTv.setText(name);
            nameTv.setTextColor(builtin ? soft : ContextCompat.getColor(this, R.color.ink));
            nameTv.setTextSize(15);
            nameTv.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nameTv.setLayoutParams(nameLp);
            row.addView(nameTv);

            if (builtin) {
                TextView tag = new TextView(this);
                tag.setText(R.string.cat_builtin);
                tag.setTextColor(faint);
                tag.setTextSize(12);
                row.addView(tag);
            } else {
                TextView del = new TextView(this);
                del.setText(R.string.delete);
                del.setTextColor(red);
                del.setTextSize(13);
                del.setPadding(dp(12), dp(6), dp(4), dp(6));
                del.setOnClickListener(v -> confirmDelete(name));
                row.addView(del);
            }

            row.addView(arrowBtn("▲", i == 0 ? faint : soft, v -> {
                db.moveCategory(name, -1);
                rebuild();
            }));
            row.addView(arrowBtn("▼", i == cats.size() - 1 ? faint : soft, v -> {
                db.moveCategory(name, 1);
                rebuild();
            }));

            list.addView(row);
        }
    }

    private TextView arrowBtn(String arrow, int color, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(arrow);
        tv.setTextColor(color);
        tv.setTextSize(14);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setOnClickListener(l);
        return tv;
    }

    private void addCategory() {
        String name = etNew.getText().toString().trim();
        if (name.isEmpty()) return;
        if (!db.addCategory(name)) {
            Toast.makeText(this, R.string.err_cat_exists, Toast.LENGTH_SHORT).show();
            return;
        }
        etNew.setText("");
        rebuild();
    }

    private void confirmDelete(String name) {
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage(R.string.cat_delete_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (!db.deleteCategory(name)) {
                        Toast.makeText(this, R.string.cat_delete_in_use, Toast.LENGTH_LONG).show();
                    }
                    rebuild();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
