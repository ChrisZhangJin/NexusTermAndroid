package com.nexusterm.android

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 物理键盘检测：判断「现在有没有接着一把真键盘」，并在插拔时通知界面。
 *
 * ## 为什么自己监听 InputDevice，而不是读 Configuration.keyboard
 *
 * `Configuration.keyboard` / `hardKeyboardHidden` 只在配置变化时刷新，而且我们为了
 * 不让 Activity 被重建，已经在清单里把这几类变化声明成自己处理（见
 * AndroidManifest.xml 里 configChanges 的注释）；再加上它对「蓝牙键盘 + 触摸屏」
 * 这种组合的报告并不可靠。`InputManager.InputDeviceListener` 是设备增删的权威来源，
 * 不依赖 Activity 生命周期，所以这里只认它。
 *
 * ## 判定规则
 *
 * 一个设备算「物理键盘」当且仅当：非虚拟、支持 [InputDevice.SOURCE_KEYBOARD]、
 * 且 keyboardType 是 [InputDevice.KEYBOARD_TYPE_ALPHABETIC]（字母键盘）。
 * 排除虚拟设备是因为模拟器 / 输入法 / 某些远程控制软件会注册虚拟键盘；排除
 * 非字母键盘（遥控器、音量键之类 KEYBOARD_TYPE_NON_ALPHABETIC）是因为它们打不出
 * 终端要的字符。一把蓝牙键盘常常暴露成好几个 InputDevice（键盘本体、多媒体键、
 * 鼠标），所以整体的规则是「**任意一个**设备满足就算有键盘」，而不是逐个比对。
 */
internal fun isHardwareKeyboard(device: InputDevice?): Boolean =
    device != null &&
        !device.isVirtual &&
        device.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
        device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

/**
 * 这个按键事件是不是来自物理键盘。
 *
 * 注意：`adb shell input keyevent` 注入的事件可能带虚拟设备甚至 null 设备，
 * 所以分发层**不能**把这个函数当成放行的硬性前提（否则测试时按键全被吞掉）——
 * 它只适合用来做「锦上添花」的判断，真正放不放行以 [rememberHasHardKeyboard]
 * 的状态或事件本身为准。
 */
internal fun isHardwareKeyEvent(event: KeyEvent): Boolean = isHardwareKeyboard(event.device)

/** 扫一遍当前所有输入设备，任意一个是物理键盘就返回 true。 */
private fun scanHardwareKeyboard(): Boolean =
    InputDevice.getDeviceIds().any { id -> isHardwareKeyboard(InputDevice.getDevice(id)) }

/**
 * 当前是否接着物理键盘，随插拔实时更新。
 *
 * 进入组合时先同步扫一遍作为初值（这样第一帧就是对的，不会先按「没键盘」画一次
 * 再闪一下），然后在 [DisposableEffect] 里注册 [InputManager.InputDeviceListener]，
 * 离开组合时注销，避免泄漏 Activity。监听器挂在主线程 Looper 的 Handler 上，回调
 * 里直接写 Compose 状态是安全的。
 *
 * 蓝牙键盘会频繁触发 onInputDeviceChanged（电量、LED、布局变化等），而每次回调
 * 都要重新扫描全部设备，所以只在**结果真的变了**时才写状态，免得下游无谓重组。
 *
 * 回调里不用参数里的 deviceId 去判断，而是整体重扫：设备被移除后 getDevice(id) 已经
 * 返回 null，没法判断它「原来是不是键盘」，重扫是最简单且不会漏的做法。
 */
@Composable
fun rememberHasHardKeyboard(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(scanHardwareKeyboard()) }

    DisposableEffect(context) {
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val listener = object : InputManager.InputDeviceListener {
            private fun refresh() {
                val now = scanHardwareKeyboard()
                if (state.value != now) state.value = now
            }

            override fun onInputDeviceAdded(deviceId: Int) = refresh()
            override fun onInputDeviceRemoved(deviceId: Int) = refresh()
            override fun onInputDeviceChanged(deviceId: Int) = refresh()
        }
        inputManager?.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        // 注册和上面的初始扫描之间若恰好插拔过一次，初值就过期了，补扫一次对齐。
        listener.onInputDeviceChanged(-1)
        onDispose { inputManager?.unregisterInputDeviceListener(listener) }
    }

    return state
}
