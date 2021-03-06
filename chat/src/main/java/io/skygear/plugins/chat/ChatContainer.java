package io.skygear.plugins.chat;


import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import org.joda.time.DateTime;
import org.joda.time.convert.Converter;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.skygear.skygear.Asset;
import io.skygear.skygear.AssetPostRequest;
import io.skygear.skygear.AssetSerializer;
import io.skygear.skygear.AuthenticationException;
import io.skygear.skygear.Container;
import io.skygear.skygear.Database;
import io.skygear.skygear.Error;
import io.skygear.skygear.LambdaResponseHandler;
import io.skygear.skygear.PubsubContainer;
import io.skygear.skygear.PubsubHandler;
import io.skygear.skygear.Query;
import io.skygear.skygear.Record;
import io.skygear.skygear.RecordQueryResponseHandler;
import io.skygear.skygear.RecordSaveResponseHandler;
import io.skygear.skygear.Reference;

/**
 * The Container for Chat Plugin
 */
public final class ChatContainer {
    private static final int GET_MESSAGES_DEFAULT_LIMIT = 50; // default value
    private static final String TAG = "SkygearChatContainer";

    private static ChatContainer sharedInstance;

    private final Container skygear;
    private final Map<String, Subscription> messageSubscription = new HashMap<>();
    private final Map<String, Subscription> typingSubscription = new HashMap<>();

    /* --- Constructor --- */

    /**
     * Gets the shared instance.
     *
     * @param container the container
     * @return the instance
     */
    public static ChatContainer getInstance(@NonNull final Container container) {
        if (sharedInstance == null) {
            sharedInstance = new ChatContainer(container);
        }

        return sharedInstance;
    }

    private ChatContainer(final Container container) {
        if (container != null) {
            this.skygear = container;
        } else {
            throw new NullPointerException("Container can't be null");
        }
    }

    /* --- Conversation --- */

    /**
     * Create a conversation.
     *
     * @param participantIds the participant ids
     * @param title          the title
     * @param metadata       the metadata
     * @param options        the options
     * @param callback       the callback
     */
    public void createConversation(@NonNull final Set<String> participantIds,
                                   @Nullable final String title,
                                   @Nullable final Map<String, Object> metadata,
                                   @Nullable final Map<Conversation.OptionKey, Object> options,
                                   @Nullable final SaveCallback<Conversation> callback) {
        this.skygear.callLambdaFunction("chat:create_conversation",
                new Object[] {
                        new JSONArray(participantIds),
                        title,
                        metadata == null ? null : new JSONObject(metadata),
                        options == null ? null : new JSONObject(convertOptionsMap(options))
                },
                new LambdaResponseHandler(){
                    @Override
                    public void onLambdaSuccess(JSONObject result){
                        try {
                            Conversation conversation = Conversation.fromJson((JSONObject) result.get("conversation"));
                            if (callback != null) {
                                callback.onSucc(conversation);
                            }
                        } catch (JSONException e)
                        {
                            if (callback != null) {
                                callback.onFail(e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onLambdaFail(Error error) {

                        if (callback != null) {
                            callback.onFail(error.getMessage());
                        }
                    }
        });
    }

    /**
     * Create a direct conversation.
     *
     * @param participantId the participant id
     * @param title         the title
     * @param metadata      the metadata
     * @param callback      the callback
     */
    public void createDirectConversation(@NonNull final String participantId,
                                         @Nullable final String title,
                                         @Nullable final Map<String, Object> metadata,
                                         @Nullable final SaveCallback<Conversation> callback) {
        Set<String> participantIds = new HashSet<>();
        participantIds.add(this.skygear.getAuth().getCurrentUser().getId());
        participantIds.add(participantId);

        Map<Conversation.OptionKey, Object> options = new HashMap<>();
        options.put(Conversation.OptionKey.DISTINCT_BY_PARTICIPANTS, true);
        createConversation(participantIds, title, metadata, options, callback);
    }

    private Map<String, Object> convertOptionsMap(Map<Conversation.OptionKey, Object> options) {
        HashMap<String, Object> newOptions = new HashMap<String, Object>();
        for (Map.Entry<Conversation.OptionKey, Object> option: options.entrySet()) {
            newOptions.put(option.getKey().getValue(), option.getValue());
        }
        return newOptions;
    }


    /**
     * Gets all conversations with last_message and last_read_message.
     *
     * @param callback the callback
     */

    public void getConversations(@Nullable final GetCallback<List<Conversation>> callback) {
        this.getConversations(callback, true);
    }


    /**
     * Gets conversation.
     *
     * @param conversationId the conversation id
     * @param callback       the callback
     * @param getLastMessage get last_message and last_read_message if getLastMessage is true
     */
    public void getConversation(@NonNull final String conversationId,
                                @Nullable final GetCallback<Conversation> callback, boolean getLastMessage) {
        this.getConversation(
                conversationId,
                getLastMessage,
                new GetCallback<Conversation>() {
                    @Override
                    public void onSucc(@Nullable Conversation conversation) {
                        if (callback != null) {

                            callback.onSucc(conversation);
                        }
                    }

                    @Override
                    public void onFail(@Nullable String failReason) {
                        if (callback != null) {
                            callback.onFail(failReason);
                        }
                    }
                });
    }

    /**
     * Gets conversation.
     *
     * @param conversationId the conversation id
     * @param callback       the callback
     */
    public void getConversation(@NonNull final String conversationId,
                                @Nullable final GetCallback<Conversation> callback) {
        this.getConversation(conversationId, true, callback);
    }

    /**
     * Sets conversation title.
     *
     * @param conversation the conversation
     * @param title        the title
     * @param callback     the callback
     */
    public void setConversationTitle(@NonNull final Conversation conversation,
                                     @NonNull final String title,
                                     @Nullable final SaveCallback<Conversation> callback) {
        Map<String, Object> map = new HashMap<>();
        map.put(Conversation.TITLE_KEY, title);

        this.updateConversation(conversation, map, callback);
    }


    private void updateConversationMembership(@NonNull final Conversation conversation,
                                              @NonNull final String lambda,
                                              @NonNull final List<String> memberIds,
                                              @Nullable final SaveCallback<Conversation> callback)
    {
        this.skygear.callLambdaFunction(lambda,
                new Object[]{conversation.getId(), new JSONArray(memberIds)},
                new LambdaResponseHandler(){
                    @Override
                    public void onLambdaSuccess(JSONObject result){
                        try {
                            Conversation conversation = Conversation.fromJson((JSONObject) result.get("conversation"));
                            if (callback != null) {
                                callback.onSucc(conversation);
                            }
                        } catch (JSONException e)
                        {
                            if (callback != null) {
                                callback.onFail(e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onLambdaFail(Error error) {

                        if (callback != null) {
                            callback.onFail(error.getMessage());
                        }
                    }
                });

    }

    /**
     * Add conversation admin.
     *
     * @param conversation the conversation
     * @param adminId      the admin id
     * @param callback     the callback
     */
    public void addConversationAdmin(@NonNull final Conversation conversation,
                                     @NonNull final String adminId,
                                     @Nullable final SaveCallback<Conversation> callback) {
        addConversationAdmins(conversation, Arrays.asList(adminId), callback);
    }

    /**
     * Add conversation admins.
     *
     * @param conversation the conversation
     * @param adminIds     the admin ids
     * @param callback     the callback
     */
    public void addConversationAdmins(@NonNull final Conversation conversation,
                                      @NonNull final List<String> adminIds,
                                      @Nullable final SaveCallback<Conversation> callback) {
        updateConversationMembership(conversation, "chat:add_admins", adminIds, callback);
    }

    /**
     * Remove conversation admins.
     *
     * @param conversation the conversation
     * @param adminIds      the admin ids
     * @param callback     the callback
     */
    public void removeConversationAdmins(@NonNull final Conversation conversation,
                                        @NonNull final List<String> adminIds,
                                        @Nullable final SaveCallback<Conversation> callback) {
        updateConversationMembership(conversation, "chat:remove_admins", adminIds, callback);
    }

    /**
     * Remove conversation admin.
     *
     * @param conversation the conversation
     * @param adminId      the admin id
     * @param callback     the callback
     */
    public void removeConversationAdmin(@NonNull final Conversation conversation,
                                         @NonNull final String adminId,
                                         @Nullable final SaveCallback<Conversation> callback) {
        removeConversationAdmins(conversation, Arrays.asList(adminId), callback);
    }


    /**
     * Add conversation participants.
     *
     * @param conversation  the conversation
     * @param participantIds the participant ids
     * @param callback      the callback
     */
    public void addConversationParticipants(@NonNull final Conversation conversation,
                                           @NonNull final List<String> participantIds,
                                           @Nullable final SaveCallback<Conversation> callback) {
        updateConversationMembership(conversation, "chat:add_participants", participantIds, callback);
    }

    /**
     * Add conversation participant.
     *
     * @param conversation  the conversation
     * @param participantId the participant id
     * @param callback      the callback
     */
    public void addConversationParticipant(@NonNull final Conversation conversation,
                                            @NonNull final String participantId,
                                            @Nullable final SaveCallback<Conversation> callback) {
        addConversationParticipants(conversation, Arrays.asList(participantId), callback);
    }

    /**
     * Remove conversation participants.
     *
     * @param conversation  the conversation
     * @param participantIds the participant ids
     * @param callback      the callback
     */
    public void removeConversationParticipants(@NonNull final Conversation conversation,
                                               @NonNull final List<String> participantIds,
                                               @Nullable final SaveCallback<Conversation> callback) {
        updateConversationMembership(conversation, "chat:remove_participants", participantIds, callback);
    }

    /**
     * Remove conversation participant.
     *
     * @param conversation  the conversation
     * @param participantId the participant id
     * @param callback      the callback
     */
    public void removeConversationParticipant(@NonNull final Conversation conversation,
                                              @NonNull final String participantId,
                                              @Nullable final SaveCallback<Conversation> callback) {
        removeConversationParticipants(conversation, Arrays.asList(participantId), callback);
    }

    /**
     * Sets whether the conversation is distinct by participants.
     *
     * @param conversation             the conversation
     * @param isDistinctByParticipants the boolean indicating whether it is distinct by participants
     * @param callback                 the callback
     */
    public void setConversationDistinctByParticipants(@NonNull final Conversation conversation,
                                                      @NonNull final boolean isDistinctByParticipants,
                                                      @Nullable final SaveCallback<Conversation> callback) {
        Map<String, Object> map = new HashMap<>();
        map.put(Conversation.DISTINCT_BY_PARTICIPANTS_KEY, isDistinctByParticipants);

        this.updateConversation(conversation, map, callback);
    }

    /**
     * Sets conversation metadata.
     *
     * @param conversation the conversation
     * @param metadata     the metadata
     * @param callback     the callback
     */
    public void setConversationMetadata(@NonNull final Conversation conversation,
                                        @NonNull final Map<String, Object> metadata,
                                        @Nullable final SaveCallback<Conversation> callback) {
        JSONObject metadataJSON = new JSONObject(metadata);
        Map<String, Object> map = new HashMap<>();
        map.put(Conversation.METADATA_KEY, metadataJSON);

        this.updateConversation(conversation, map, callback);
    }

    /**
     * Leave a conversation.
     *
     * @param conversation the conversation
     * @param callback     the callback
     */
    public void leaveConversation(@NonNull final Conversation conversation,
                                  @Nullable final LambdaResponseHandler callback) {
        this.skygear.callLambdaFunction("chat:leave_conversation",
                                        new Object[]{conversation.getId()},
                                        callback);
    }

    /**
     * Delete a conversation.
     *
     * @param conversation  the conversation
     * @param callback     the callback
     */
    public void deleteConversation(@NonNull final Conversation conversation,
                                  @Nullable final DeleteCallback<Boolean> callback) {
        this.skygear.callLambdaFunction("chat:delete_conversation",
                new Object[]{conversation.getId()},
                new LambdaResponseHandler() {
                    @Override
                    public void onLambdaSuccess(JSONObject result) {
                        callback.onSucc(true);
                    }

                    @Override
                    public void onLambdaFail(Error reason) {
                        if (callback != null) {
                            callback.onFail(reason.getMessage());
                        }
                    }
                });
    }

    /**
     * Update a conversation.
     *
     * @param conversation the conversation
     * @param updates      the updates
     * @param callback     the callback
     */
    public void updateConversation(@NonNull final Conversation conversation,
                                   @NonNull final Map<String, Object> updates,
                                   @Nullable final SaveCallback<Conversation> callback) {
        final Database publicDB = this.skygear.getPublicDatabase();

        this.getConversation(conversation.getId(), true, new GetCallback<Conversation>() {
            @Override
            public void onSucc(@Nullable final Conversation conversation) {
                if (callback == null) {
                    // nothing to do
                    return;
                }

                if (conversation == null) {
                    callback.onFail("Cannot find the conversation");
                    return;
                }

                Record conversationRecord = conversation.record;
                for (Map.Entry<String, Object> entry : updates.entrySet()) {
                    conversationRecord.set(entry.getKey(), entry.getValue());
                }
                publicDB.save(conversationRecord, new SaveResponseAdapter<Conversation>(callback) {
                    @Override
                    public Conversation convert(Record record) {
                        return new Conversation(record);
                    }
                });
            }

            @Override
            public void onFail(@Nullable String failReason) {
                if (callback != null) {
                    callback.onFail(failReason);
                }
            }
        });
    }

    /**
     * Mark last read message of a conversation.
     *
     * @param conversation the conversation
     * @param message      the message
     */
    public void markConversationLastReadMessage(@NonNull final Conversation conversation,
                                                @NonNull final Message message) {
        markMessagesAsRead(Arrays.asList(new Message[]{message}));
    }

    /**
     * Gets total unread message count.
     *
     * @param callback the callback
     */
    public void getTotalUnreadMessageCount(@Nullable final GetCallback<Integer> callback) {
        this.skygear.callLambdaFunction("chat:total_unread", null, new LambdaResponseHandler() {
            @Override
            public void onLambdaSuccess(JSONObject result) {
                try {
                    int count = result.getInt("message");
                    if (callback != null) {
                        callback.onSucc(count);
                    }
                } catch (JSONException e) {
                    if (callback != null) {
                        callback.onFail(e.getMessage());
                    }
                }
            }

            @Override
            public void onLambdaFail(Error reason) {
                if (callback != null) {
                    callback.onFail(reason.getMessage());
                }
            }
        });
    }

    /* --- Conversation (Private) --- */

    /**
     * Gets single conversation for current user.
     *
     * @param conversationId  the ID of conversation
     * @param getLastMessages if true, then last_message and last_read_message are fetched.
     * @param callback        the callback
     */

    private void getConversation(@NonNull final String conversationId,
                                      @NonNull final boolean getLastMessages,
                                      @Nullable final GetCallback<Conversation> callback) {
        this.skygear.callLambdaFunction("chat:get_conversation",
                new Object[]{conversationId, getLastMessages},
                new LambdaResponseHandler(){
                    @Override
                    public void onLambdaSuccess(JSONObject result){
                        try {
                            Conversation conversation = Conversation.fromJson(result.getJSONObject("conversation"));
                            if (callback != null) {
                                callback.onSucc(conversation);
                            }
                        } catch (JSONException e)
                        {
                            if (callback != null) {
                                callback.onFail(e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onLambdaFail(Error error) {

                        if (callback != null) {
                            callback.onFail(error.getMessage());
                        }
                    }
                });
    }

    /**
     * Gets all conversations for current user.
     *
     * @param getLastMessages if true, then last_message and last_read_message are fetched.
     * @param callback        the callback
     */

    public void getConversations(@Nullable final GetCallback<List<Conversation>> callback,
                                  @NonNull final Boolean getLastMessages
    ) {
        this.skygear.callLambdaFunction("chat:get_conversations",
                new Object[]{1, 50, getLastMessages},
                new LambdaResponseHandler() {
                    @Override
                    public void onLambdaSuccess(JSONObject result) {
                        try {
                            JSONArray items = result.getJSONArray("conversations");
                            ArrayList<Conversation> conversations = new ArrayList<>();
                            int n = items.length();
                            for (int i = 0; i < n; i++) {
                                JSONObject o = items.getJSONObject(i);
                                conversations.add(Conversation.fromJson(o));
                            }
                            if (callback != null) {
                                callback.onSucc(conversations);
                            }
                        } catch (JSONException e) {
                            if (callback != null) {
                                callback.onFail(e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onLambdaFail(Error error) {

                        if (callback != null) {
                            callback.onFail(error.getMessage());
                        }
                    }
                });
    }
    /* --- Message --- */
    /**
     * Gets messages.
     *
     * @param conversation the conversation
     * @param limit        the limit
     * @param before       the before
     * @param order        the order, either 'edited_at' or '_created_at'
     * @param callback     the callback
     */
    public void getMessages(@NonNull final Conversation conversation,
                            final int limit,
                            @Nullable final Date before,
                            @Nullable final String order,
                            @Nullable final GetCallback<List<Message>> callback) {
        int limitCount = limit;
        String beforeTimeISO8601 = DateUtils.toISO8601(before != null ? before : new Date());

        if (limitCount <= 0) {
            limitCount = GET_MESSAGES_DEFAULT_LIMIT;
        }

        Object[] args = new Object[]{conversation.getId(), limitCount, beforeTimeISO8601, order};
        this.skygear.callLambdaFunction("chat:get_messages", args, new LambdaResponseHandler() {
            @Override
            public void onLambdaSuccess(JSONObject result) {
                List<Message> messages = null;
                JSONArray results = result.optJSONArray("results");

                if (results != null) {
                    messages = new ArrayList<>(results.length());

                    for (int i = 0; i < results.length(); i++) {
                        try {
                            JSONObject object = results.getJSONObject(i);
                            Record record = Record.fromJson(object);
                            Message message = new Message(record);
                            messages.add(message);
                        } catch (JSONException e) {
                            Log.e(TAG, "Fail to get message: " + e.getMessage());
                        }
                    }

                    ChatContainer.this.markMessagesAsDelivered(messages);
                }
                if (callback != null) {
                    callback.onSucc(messages);
                }
            }

            @Override
            public void onLambdaFail(Error reason) {
                if (callback != null) {
                    callback.onFail(reason.getMessage());
                }
            }
        });
    }

    /**
     * Send message.
     *
     * @param conversation the conversation
     * @param body         the body
     * @param asset        the asset
     * @param metadata     the metadata
     * @param callback     the callback
     */
    public void sendMessage(@NonNull final Conversation conversation,
                            @Nullable final String body,
                            @Nullable final Asset asset,
                            @Nullable final JSONObject metadata,
                            @Nullable final SaveCallback<Message> callback) {
        if (!StringUtils.isEmpty(body) || asset != null || metadata != null) {
            Record record = new Record("message");
            Reference reference = new Reference("conversation", conversation.getId());
            record.set("conversation", reference);
            if (body != null) {
                record.set("body", body);
            }
            if (metadata != null) {
                record.set("metadata", metadata);
            }

            if (asset == null) {
                this.saveMessageRecord(record, callback);
            } else {
                this.saveMessageRecord(record, asset, callback);
            }
        } else {
            if (callback != null) {
                callback.onFail("Please provide either body, asset or metadata");
            }
        }
    }

    /**
     * Mark a message as read.
     *
     * @param message the message
     */
    public void markMessageAsRead(@NonNull Message message) {
        List<Message> messages = new LinkedList<>();
        messages.add(message);

        this.markMessagesAsRead(messages);
    }

    /**
     * Mark some messages as read.
     *
     * @param messages the messages
     */
    public void markMessagesAsRead(@NonNull List<Message> messages) {
        JSONArray messageIds = new JSONArray();
        for (Message eachMessage : messages) {
            messageIds.put(eachMessage.getId());
        }

        this.skygear.callLambdaFunction(
                "chat:mark_as_read",
                new Object[]{messageIds},
                new LambdaResponseHandler() {
                    @Override
                    public void onLambdaSuccess(JSONObject result) {
                        Log.i(TAG, "Successfully mark messages as read");
                    }

                    @Override
                    public void onLambdaFail(Error reason) {
                        Log.w(TAG, "Fail to mark messages as read: " + reason.getMessage());
                    }
                });
    }

    /**
     * Mark a message as delivered.
     *
     * @param message the message
     */
    public void markMessageAsDelivered(@NonNull Message message) {
        List<Message> messages = new LinkedList<>();
        messages.add(message);

        this.markMessagesAsDelivered(messages);
    }

    /**
     * Mark some messages as delivered.
     *
     * @param messages the messages
     */
    public void markMessagesAsDelivered(@NonNull List<Message> messages) {
        JSONArray messageIds = new JSONArray();
        for (Message eachMessage : messages) {
            messageIds.put(eachMessage.getId());
        }

        this.skygear.callLambdaFunction(
                "chat:mark_as_delivered",
                new Object[]{messageIds},
                new LambdaResponseHandler() {
                    @Override
                    public void onLambdaSuccess(JSONObject result) {
                        Log.i(TAG, "Successfully mark messages as delivered");
                    }

                    @Override
                    public void onLambdaFail(Error reason) {
                        Log.w(TAG, "Fail to mark messages as delivered: " + reason.getMessage());
                    }
                });
    }

    /**
     * Add Message to conversation
     *
     * @param message the message to be edited
     * @param conversation the conversation
     * @param callback save callback
     */

    public void addMessage(@NonNull Message message,
                           @NonNull final Conversation conversation,
                           @Nullable final SaveCallback<Message> callback)
    {
        Record record = message.getRecord();
        Reference reference = new Reference("conversation", conversation.getId());
        record.set("conversation", reference);

        if (message.getAsset() == null) {
            this.saveMessageRecord(record, callback);
        } else {
            this.saveMessageRecord(record, message.getAsset(), callback);
        }
    }

    /**
     * Edit Message
     *
     * @param message the message to be edited
     * @param body    the new message body
     * @param callback save callback
     */

    public void editMessage(@NonNull Message message,
                            @NonNull String body,
                            @Nullable final SaveCallback<Message> callback)
    {
        message.setBody(body);
        this.saveMessageRecord(message.getRecord(), callback);
    }


    /**
     * Delete a message
     *
     * @param message the mesqqsage to be deleted
     * @param callback
     */

    public void deleteMessage(@NonNull final Message message, @Nullable final DeleteCallback<Message> callback)
    {
        this.skygear.callLambdaFunction(
                "chat:delete_message",
                new Object[]{ message.getId() },
                new LambdaResponseHandler() {
                    @Override
                    public void onLambdaSuccess(JSONObject result) {
                        if (callback == null) {
                            return;
                        }

                        callback.onSucc(message);
                    }

                    @Override
                    public void onLambdaFail(Error reason) {
                        if (callback != null) {
                            callback.onFail(reason.getMessage());
                        }
                    }
                }
        );

    }

    private void saveMessageRecord(final Record message,
                                   @Nullable final SaveCallback<Message> callback) {
        this.skygear.getPublicDatabase().save(
                message,
                new SaveResponseAdapter<Message>(callback) {
                    @Override
                    public Message convert(Record record) {
                        return new Message(record);
                    }
                }
        );
    }

    private void saveMessageRecord(final Record message,
                                   final Asset asset,
                                   @Nullable final SaveCallback<Message> callback) {
        this.skygear.getPublicDatabase().uploadAsset(asset, new AssetPostRequest.ResponseHandler() {
            @Override
            public void onPostSuccess(Asset asset, String response) {
                message.set("attachment", asset);
                ChatContainer.this.saveMessageRecord(message, callback);
            }

            @Override
            public void onPostFail(Asset asset, Error reason) {
                Log.w(TAG, "Fail to upload asset: " + reason.getMessage());
                ChatContainer.this.saveMessageRecord(message, callback);
            }
        });
    }

    /* --- Message Receipt --- */

    /**
     * Gets the receipts for a message .
     *
     * @param message  the message
     * @param callback the callback
     */
    public void getMessageReceipt(@NonNull final Message message,
                                  @Nullable final GetCallback<List<MessageReceipt>> callback) {
        this.skygear.callLambdaFunction(
                "chat:get_receipt",
                new Object[]{ message.getId() },
                new LambdaResponseHandler() {
                    @Override
                    public void onLambdaSuccess(JSONObject result) {
                        if (callback == null) {
                            // nothing to do
                            return;
                        }

                        try {
                            List<MessageReceipt> receiptList = new LinkedList<>();
                            JSONArray receipts = result.getJSONArray("receipts");
                            for (int idx = 0; idx < receipts.length(); idx++) {
                                JSONObject eachReceiptJSON = receipts.getJSONObject(idx);
                                receiptList.add(MessageReceipt.fromJSON(eachReceiptJSON));
                            }

                            callback.onSucc(receiptList);
                        } catch (JSONException e) {
                            callback.onFail("Fail to parse the result: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onLambdaFail(Error reason) {
                        if (callback != null) {
                            callback.onFail(reason.getMessage());
                        }
                    }
                }
        );
    }

    /* --- Typing --- */

    /**
     * Send typing indicator for a conversation.
     *
     * @param conversation the conversation
     * @param state        the state
     */
    public void sendTypingIndicator(@NonNull Conversation conversation,
                                    @NonNull Typing.State state) {
        DateTimeFormatter dateTimeFormatter = ISODateTimeFormat.dateTime().withZoneUTC();
        String timestamp = dateTimeFormatter.print(new DateTime());
        Object[] args = {conversation.getId(), state.getName(), timestamp};
        this.skygear.callLambdaFunction("chat:typing", args, new LambdaResponseHandler(){
            @Override
            public void onLambdaSuccess(JSONObject result) {
                Log.i(TAG, "Successfully send typing indicator");
            }

            @Override
            public void onLambdaFail(Error reason) {
                Log.i(TAG, "Fail to send typing indicator: " + reason.getMessage());
            }
        });
    }

    /**
     * Subscribe typing indicator for a conversation.
     *
     * @param conversation the conversation
     * @param callback     the callback
     */
    public void subscribeTypingIndicator(@NonNull Conversation conversation,
                                         @Nullable final TypingSubscriptionCallback callback) {
        final PubsubContainer pubsub = this.skygear.getPubsub();
        final String conversationId = conversation.getId();

        if (typingSubscription.get(conversationId) == null) {
            getOrCreateUserChannel(new GetCallback<Record>() {
                @Override
                public void onSucc(@Nullable Record userChannelRecord) {
                    if (userChannelRecord != null) {
                        Subscription subscription = new Subscription(
                                conversationId,
                                (String) userChannelRecord.get("name"),
                                callback
                        );
                        subscription.attach(pubsub);
                        typingSubscription.put(conversationId, subscription);
                    }
                }

                @Override
                public void onFail(@Nullable String failReason) {
                    Log.w(TAG, "Fail to subscribe typing indicator: " + failReason);
                    if (callback != null) {
                        callback.onSubscriptionFail(failReason);
                    }
                }
            });
        }
    }

    /**
     * Unsubscribe typing indicator for a conversation.
     *
     * @param conversation the conversation
     */
    public void unsubscribeTypingIndicator(@NonNull Conversation conversation) {
        final PubsubContainer pubsub = this.skygear.getPubsub();
        String conversationId = conversation.getId();
        Subscription subscription = typingSubscription.get(conversationId);

        if (subscription != null) {
            subscription.detach(pubsub);
            typingSubscription.remove(conversationId);
        }
    }

    /* --- Chat User --- */

    /**
     * Gets users for the chat plugins.
     *
     * @param callback the callback
     */
    public void getChatUsers(@Nullable final GetCallback<List<ChatUser>> callback) {
        Query query = new Query("user");
        Database publicDB = this.skygear.getPublicDatabase();
        publicDB.query(query, new QueryResponseAdapter<List<ChatUser>>(callback) {
            @Override
            public List<ChatUser> convert(Record[] records) {
                List<ChatUser> users = new ArrayList<>(records.length);

                for (Record record : records) {
                    users.add(new ChatUser(record));
                }

                return users;
            }
        });
    }

    /* --- Subscription--- */

    /**
     * Subscribe conversation message.
     *
     * @param conversation the conversation
     * @param callback     the callback
     */
    public void subscribeConversationMessage(@NonNull final Conversation conversation,
                                             @Nullable final MessageSubscriptionCallback callback) {
        final PubsubContainer pubsub = this.skygear.getPubsub();
        final String conversationId = conversation.getId();

        if (messageSubscription.get(conversationId) == null) {
            getOrCreateUserChannel(new GetCallback<Record>() {
                @Override
                public void onSucc(@Nullable Record userChannelRecord) {
                    if (userChannelRecord != null) {
                        Subscription subscription = new Subscription(
                                conversationId,
                                (String) userChannelRecord.get("name"),
                                callback
                        );
                        subscription.attach(pubsub);
                        messageSubscription.put(conversationId, subscription);
                    }
                }

                @Override
                public void onFail(@Nullable String failReason) {
                    Log.w(TAG, "Fail to subscribe conversation message: " + failReason);
                    if (callback != null) {
                        callback.onSubscriptionFail(failReason);
                    }
                }
            });
        }
    }

    /**
     * Unsubscribe conversation message.
     *
     * @param conversation the conversation
     */
    public void unsubscribeConversationMessage(@NonNull final Conversation conversation) {
        final PubsubContainer pubsub = this.skygear.getPubsub();
        String conversationId = conversation.getId();
        Subscription subscription = messageSubscription.get(conversationId);

        if (subscription != null) {
            subscription.detach(pubsub);
            messageSubscription.remove(conversationId);
        }
    }

    private void getOrCreateUserChannel(@Nullable final GetCallback<Record> callback) {
        try {
            Query query = new Query("user_channel");
            Database privateDatabase = this.skygear.getPrivateDatabase();
            privateDatabase.query(query, new RecordQueryResponseHandler() {
                @Override
                public void onQuerySuccess(Record[] records) {
                    if (records.length != 0) {
                        if (callback != null) {
                            callback.onSucc(records[0]);
                        }
                    } else {
                        createUserChannel(callback);
                    }
                }

                @Override
                public void onQueryError(Error reason) {
                    if (callback != null) {
                        callback.onFail(reason.getMessage());
                    }
                }
            });
        } catch (AuthenticationException e) {
            if (callback != null) {
                callback.onFail(e.getMessage());
            }
        }
    }

    private void createUserChannel(final GetCallback<Record> callback) {
        try {
            Record conversation = new Record("user_channel");
            conversation.set("name", UUID.randomUUID().toString());

            RecordSaveResponseHandler handler = new RecordSaveResponseHandler() {
                @Override
                public void onSaveSuccess(Record[] records) {
                    Record record = records[0];
                    if (callback != null) {
                        callback.onSucc(record);
                    }
                }

                @Override
                public void onPartiallySaveSuccess(
                        Map<String, Record> successRecords,
                        Map<String, Error> reasons) {

                }

                @Override
                public void onSaveFail(Error reason) {
                    if (callback != null) {
                        callback.onFail(reason.getMessage());
                    }
                }
            };

            Database db = this.skygear.getPrivateDatabase();
            db.save(conversation, handler);
        } catch (AuthenticationException e) {
            callback.onFail(e.getMessage());
        }
    }

}
