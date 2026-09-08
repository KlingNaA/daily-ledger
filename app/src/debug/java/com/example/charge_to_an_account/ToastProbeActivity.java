package com.example.charge_to_an_account;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 仅 debug 构建的 Toast 探针：弹一个真实 View Toast 后立即退出，
 * 供 adb 触发并验证 PayAccessibilityService 的 Toast 事件通道（微信支付完成即此形态）。
 * 用法: adb shell am start -n com.example.charge_to_an_account/.ToastProbeActivity
 * 位于 src/debug，release 构建不编译。NoDisplay 主题要求 onResume 前调用 finish()。
 */
public class ToastProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Toast.makeText(this, "已支付￥9.99", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        finish();
    }
}
