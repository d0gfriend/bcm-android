package com.bcm.messenger.chats.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.bcm.messenger.chats.R
import com.bcm.messenger.chats.bean.MessageListItem
import com.bcm.messenger.chats.group.logic.GroupLogic
import com.bcm.messenger.chats.privatechat.logic.MessageSender
import com.bcm.messenger.common.ARouterConstants
import com.bcm.messenger.common.core.AmeGroupMessage
import com.bcm.messenger.common.crypto.MasterSecret
import com.bcm.messenger.common.database.records.ThreadRecord
import com.bcm.messenger.common.event.TextSendEvent
import com.bcm.messenger.common.mms.GlideRequests
import com.bcm.messenger.common.provider.AMELogin
import com.bcm.messenger.common.provider.AmeModuleCenter
import com.bcm.messenger.common.recipients.Recipient
import com.bcm.messenger.common.sms.OutgoingLocationMessage
import com.bcm.messenger.common.ui.CommonSearchBar
import com.bcm.messenger.common.ui.adapter.LinearBaseAdapter
import com.bcm.messenger.common.ui.popup.AmePopup
import com.bcm.messenger.common.ui.popup.BcmPopupMenu
import com.bcm.messenger.common.ui.popup.bottompopup.AmeBottomPopup
import com.bcm.messenger.common.utils.getAttrColor
import com.bcm.messenger.common.utils.getColor
import com.bcm.messenger.common.utils.getString
import com.bcm.messenger.utility.Conversions
import com.bcm.messenger.utility.StringAppearanceUtil
import com.bcm.messenger.utility.logger.ALog
import com.bcm.route.api.BcmRouter
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.chats_message_list_header_friend_requset.view.*
import kotlinx.android.synthetic.main.chats_message_list_header_multi_device.view.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashSet

/**
 * Created by wjh on 2019/7/24
 */
class MessageListAdapter(context: Context,
                         var masterSecret: MasterSecret,
                         val glideRequests: GlideRequests,
                         private val local: Locale,
                         private val delegate: IThreadHolderDelete) :
        LinearBaseAdapter<ThreadRecord>(context) {

    companion object {
        private const val TAG = "MessageListAdapter"
    }

    interface IThreadHolderDelete {
        fun onViewClicked(adapter: MessageListAdapter, viewHolder: RecyclerView.ViewHolder)
        fun setPin(threadId: Long, isPinned: Boolean)
        fun deleteConversation(recipient: Recipient?, threadId: Long)
        fun setRead(threadId: Long, isUnread: Boolean)
    }

    private var mDigest = MessageDigest.getInstance("SHA1")
    private var inflater: LayoutInflater
    private var batchMode = false
    private val batchSet = Collections.synchronizedSet(HashSet<Long>())
    private var mHeaderSearch: Int = 0
    //private var mHeaderMultiDevice = 0
    private var mHeaderRequest = 0

    private var mFriendUnhandledCount = 0
    private var mFriendUnreadCount = 0

    init {
        try {
            setHasStableIds(true)
            this.inflater = LayoutInflater.from(context)
            mHeaderSearch = addHeader()
            //mHeaderMultiDevice = addHeader()
            mHeaderRequest = addHeader()

            notifyMainChanged()

        } catch (nsae: NoSuchAlgorithmException) {
            throw AssertionError("SHA-1 missing")
        }
    }

    fun updateMasterSecret(masterSecret: MasterSecret) {
        this.masterSecret = masterSecret

        setDataList(listOf())
    }

    fun setThreadList(threadList: List<ThreadRecord>) {
        setDataList(threadList)
    }


    fun getUnreadCount(position: Int): Int {
        try {
            val data = getMainData(position)
            return data.data?.unreadCount ?: 0
        } catch (ex: Exception) {
            ALog.e(TAG, "getUnreadCount error", ex)
        }
        return 0
    }

//    fun updateMultiDevice(info: MultiDeviceInfo?) {
//        multiDeviceInfo = info
//        val headerList = getHeaderDataList()
//        for (header in headerList) {
//            if (header.type == mHeaderMultiDevice) {
//                header.view?.let {
//                    updateMultiDeviceName(it)
//                }
//                break
//            }
//        }
//    }

    fun updateFriendRequest(unhandledCount: Int, unreadCount: Int) {
        var notify = false
        if (unhandledCount != mFriendUnhandledCount) {
            this.mFriendUnhandledCount = unhandledCount
            notify = true
        }
        if (unreadCount != mFriendUnreadCount) {
            this.mFriendUnreadCount = unreadCount
            notify = true
        }

        showHeader(mHeaderRequest, unhandledCount > 0 || unreadCount > 0, false)
        if (notify) {
            notifyMainChanged()
        }
    }

    override fun onCreateHeaderHolder(parent: ViewGroup, viewType: Int): ViewHolder<ThreadRecord> {
        return when (viewType) {
            mHeaderSearch -> {
                ViewHolder(CommonSearchBar(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    val lr = resources.getDimensionPixelSize(R.dimen.common_horizontal_gap)
                    val tb = resources.getDimensionPixelSize(R.dimen.common_vertical_gap)
                    setPadding(lr, tb, lr, 0)
                    setMode(CommonSearchBar.MODE_DISPLAY)
                    setOnSearchActionListener(object : CommonSearchBar.OnSearchActionListener {
                        override fun onJump() {
                            AmeModuleCenter.contact(AMELogin.majorContext)?.openSearch(context)
                        }

                        override fun onSearch(keyword: String) {
                        }

                        override fun onClear() {
                        }

                    })
                })
            }
//            mHeaderMultiDevice -> {
//                TODO: Waiting merge
//            }
            mHeaderRequest -> {
                FriendRequestViewHolder(LayoutInflater.from(getContext()).inflate(R.layout.chats_message_list_header_friend_requset, parent, false))
            }
            else -> {
                super.onCreateHeaderHolder(parent, viewType)
            }
        }
    }

    override fun onBindContentHolder(holder: ViewHolder<ThreadRecord>, trueData: ThreadRecord?) {
        if (holder is ThreadViewHolder) {
            holder.getItem().bind(masterSecret, trueData
                    ?: return, glideRequests, local, batchSet, batchMode)
            holder.updatePin()
        }
    }

    override fun onBindHeaderHolder(holder: ViewHolder<ThreadRecord>, position: Int) {
        if (holder is FriendRequestViewHolder) {
            holder.bind()
        } else if (holder is MultiDeviceViewHolder) {
            holder.bind()
        }
    }

    override fun onCreateContentHolder(parent: ViewGroup): ViewHolder<ThreadRecord> {
        return ThreadViewHolder(inflater.inflate(R.layout.chats_list_item_view, parent, false))
    }

    override fun onViewRecycled(holder: ViewHolder<ThreadRecord>) {
        if (holder is ThreadViewHolder) {
            holder.getItem().unbind()
        }
    }

    override fun getItemId(position: Int): Long {
        val type = getItemViewType(position)
        val idText = if (type == ITEM_TYPE_DATA) {
            getMainData(position).data?.getRecipient(AMELogin.majorContext)?.address?.serialize() ?: return 0
        } else {
            "id_individual_contact_extra_$type"
        }
        val bytes = mDigest.digest(idText.toByteArray())
        return Conversions.byteArrayToLong(bytes)
    }

    inner class ThreadViewHolder(itemView: View) : LinearBaseAdapter.ViewHolder<ThreadRecord>(itemView) {

        private val chatItem: MessageListItem = itemView as MessageListItem
        private var isPinned: Boolean = false
        private val pinImg: ImageView = chatItem.findViewById(R.id.chats_pin)

        private var x = 0
        private var y = 0

        private var deleteMsgId = 0L

        init {
            chatItem.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    x = event.rawX.toInt()
                    y = event.y.toInt() - itemView.height
                }
                return@setOnTouchListener false
            }

            chatItem.setOnLongClickListener {
                val menuItems = listOf(
                        BcmPopupMenu.MenuItem(if (chatItem.unreadCount == 0) getString(R.string.chats_item_set_unread) else getString(R.string.chats_item_set_read)),
                        BcmPopupMenu.MenuItem(if (chatItem.recipient?.isMuted == true) getString(R.string.chats_item_set_not_mute) else getString(R.string.chats_item_set_mute)),
                        BcmPopupMenu.MenuItem(if (isPinned) getString(R.string.chats_item_cancel_pin) else getString(R.string.chats_item_pin)),
                        BcmPopupMenu.MenuItem(getString(R.string.chats_item_delete))
                )
                BcmPopupMenu.Builder(it.context)
                        .setMenuItem(menuItems)
                        .setAnchorView(it)
                        .setSelectedCallback { index ->
                            when (index) {
                                0 -> setUnread()
                                1 -> switchMute()
                                2 -> switchPin()
                                3 -> if (chatItem.groupId > 0) deleteGroupChat() else deletePrivateChat()
                            }
                        }
                        .show(x, y)

                return@setOnLongClickListener true
            }

            chatItem.setOnClickListener {
                delegate.onViewClicked(this@MessageListAdapter, this)
            }
        }

        private fun switchPin() {
            delegate.setPin(chatItem.threadId, !isPinned)
        }

        private fun deleteGroupChat() {
            AlertDialog.Builder(chatItem.context)
                    .setTitle(R.string.chats_item_confirm_delete_title)
                    .setMessage(R.string.chats_item_confirm_delete_message)
                    .setPositiveButton(StringAppearanceUtil.applyAppearance(getString(R.string.chats_item_delete), color = getColor(R.color.common_color_ff3737))) { _, _ ->
                        delegate.deleteConversation(chatItem.recipient, chatItem.threadId)
                    }
                    .setNegativeButton(R.string.chats_cancel, null)
                    .show()
        }

        private fun deletePrivateChat() {
            AmePopup.bottom.newBuilder()
                    .withTitle(chatItem.context.getString(R.string.chats_item_delete_private_chat_title, chatItem.recipient?.name))
                    .withPopItem(AmeBottomPopup.PopupItem(chatItem.context.getString(R.string.chats_item_delete_private_both, chatItem.recipient?.name), chatItem.context.getAttrColor(R.attr.common_text_warn_color)) {
                        realDelete()
                    })
                    .withPopItem(AmeBottomPopup.PopupItem(chatItem.context.getString(R.string.chats_item_delete_private_local), chatItem.context.getAttrColor(R.attr.common_text_warn_color)) {
                        delegate.deleteConversation(chatItem.recipient, chatItem.threadId)
                    })
                    .withDoneTitle(chatItem.context.getString(R.string.chats_cancel))
                    .show(chatItem.context as? FragmentActivity)
        }

        private fun realDelete() {
            val recipient = chatItem.recipient ?: return

            AmePopup.loading.show(chatItem.context as? FragmentActivity)
            EventBus.getDefault().register(this)
            Observable.create<Unit> { emitter ->
                val clearMessage = AmeGroupMessage(AmeGroupMessage.CONTROL_MESSAGE, AmeGroupMessage.ControlContent(AmeGroupMessage.ControlContent.ACTION_CLEAR_MESSAGE, Recipient.major().address.serialize(), "", 0L)).toString()
                val message = OutgoingLocationMessage(recipient, clearMessage, (recipient.expireMessages * 1000).toLong())
                MessageSender.send(chatItem.context, AMELogin.majorContext, message, chatItem.threadId) { messageId ->
                    deleteMsgId = messageId
                }
                emitter.onComplete()
            }.subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({
                    }, {
                        AmePopup.loading.dismiss()
                    })
        }

        @Subscribe(threadMode = ThreadMode.MAIN)
        fun onEvent(event: TextSendEvent) {
            if (event.messageId == deleteMsgId) {
                EventBus.getDefault().unregister(this)
                AmePopup.loading.dismiss()
                delegate.deleteConversation(chatItem.recipient, chatItem.threadId)
            }
        }

        private fun setUnread() {
            delegate.setRead(chatItem.threadId, chatItem.unreadCount == 0)
        }

        private fun switchMute() {
            val isMute = chatItem.recipient?.isMuted ?: true
            if (chatItem.recipient?.isGroupRecipient == true) {
                GroupLogic.get(AMELogin.majorContext).muteGroup(chatItem.groupId, !isMute) { _, _ -> }
            } else {
                val muteTime = if (isMute) {
                    0L
                } else {
                    System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650)
                }
                AmeModuleCenter.contact(AMELogin.majorContext)?.setMute(chatItem.recipient?.address?.serialize() ?: "", muteTime, null)
            }
        }

        fun updatePin() {
            isPinned = chatItem.checkPin()
            if (isPinned) {
                pinImg.visibility = View.VISIBLE
            } else {
                pinImg.visibility = View.GONE
            }
        }

        fun getItem(): MessageListItem {
            return chatItem
        }
    }

    inner class MultiDeviceViewHolder(itemView: View) : ViewHolder<ThreadRecord>(itemView) {
        init {
            itemView.header_multi_device.visibility = View.GONE
//            itemView.setOnClickListener {
//                BcmRouter.getInstance()
//                        .get(ARouterConstants.Activity.MULTI_DEVICE_MANAGE)
//                        .putLong(ARouterConstants.PARAM.PARAM_MULTI_DEVICE_DEVICE_ID, multiDeviceInfo?.deviceId ?: 0L)
//                        .navigation(getContext())
//            }
        }

        fun bind() {
            //        if (multiDeviceInfo == null) {
//            itemView.header_multi_device.visibility = View.GONE
//        } else {
//            itemView.header_multi_device.visibility = View.VISIBLE
//            itemView.header_multi_device_name.text = AppContextHolder.APP_CONTEXT.getString(R.string.chats_multi_device_header_string, multiDeviceInfo?.deviceName)
//        }
        }

    }

    inner class FriendRequestViewHolder(itemView: View) : ViewHolder<ThreadRecord>(itemView) {
        init {
            itemView.header_friend_request.visibility = View.GONE
            itemView.setOnClickListener {
                BcmRouter.getInstance()
                        .get(ARouterConstants.Activity.FRIEND_REQUEST_LIST)
                        .putSerializable(ARouterConstants.PARAM.PARAM_ACCOUNT_CONTEXT, AMELogin.majorContext)
                        .navigation(getContext())
            }
        }

        fun bind() {
            when {
                mFriendUnreadCount > 0 -> {
                    itemView.header_friend_request.visibility = View.VISIBLE
                    itemView.header_friend_request_count.text = mFriendUnreadCount.toString()
                    itemView.header_friend_request_count.setTextColor(getColor(R.color.common_color_white))
                    itemView.header_friend_request_count.setBackgroundResource(R.drawable.chats_friend_request_count_bg)
                }
                mFriendUnhandledCount > 0 -> {
                    itemView.header_friend_request.visibility = View.VISIBLE
                    itemView.header_friend_request_count.text = mFriendUnhandledCount.toString()
                    itemView.header_friend_request_count.setTextColor(getColor(R.color.common_color_A8A8A8))
                    itemView.header_friend_request_count.setBackgroundResource(0)
                }
                else -> itemView.header_friend_request.visibility = View.GONE
            }
        }
    }


}