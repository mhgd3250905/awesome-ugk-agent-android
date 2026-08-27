package com.ugk.pi.android.testapp

import com.ugk.pi.android.UserConfirmationDialogButton
import com.ugk.pi.android.UserConfirmationDialogRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadlessConfirmationDialogPresenterTest {
    @Test
    fun backgroundConfirmationSelectsCancellationButton() = runBlocking {
        val result = HeadlessConfirmationDialogPresenter.showConfirmationDialog(
            UserConfirmationDialogRequest(
                title = "确认",
                message = "执行操作",
                buttons = listOf(
                    UserConfirmationDialogButton("confirm", "确认"),
                    UserConfirmationDialogButton("cancel", "取消")
                )
            )
        )

        assertEquals("cancel", result.selectedButtonId)
    }
}
