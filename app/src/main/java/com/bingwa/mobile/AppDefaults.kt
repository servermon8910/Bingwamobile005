package com.bingwa.mobile

internal const val SMS_TAGS = "Tags: {name}  {offer}  {amount}  {phone}"

internal const val DEFAULT_TPL_SUCCESS =
    "Hi {name}, your {offer} bundle has been sent successfully to {phone}. Thank you for using Bingwa Sokoni."

internal const val DEFAULT_TPL_FAILED =
    "Hi {name}, we could not send your {offer} bundle worth KES {amount} to {phone}. Please contact us for help, a refund, or a retry."

internal const val DEFAULT_TPL_PENDING =
    "Hi {name}, we could not send {offer} to {phone} today because this line has already received the same daily offer. Your request has been scheduled for tomorrow morning and will be sent automatically when the line becomes eligible again."

internal const val DEFAULT_TPL_LIMIT_NOTICE =
    "Hi {name}, we could not send {offer} to {phone} today because this line has already bought Bingwa Sokoni data today, and Bingwa Sokoni data can only be purchased once per day on the same line. Reply 1 to send the bundle to a different number today, or reply 2 to have us send it to the same number tomorrow morning."

internal const val DEFAULT_TPL_SCHEDULED =
    "Hi {name}, your {offer} bundle has been sent to {phone} as scheduled. We were unable to send it yesterday because this line had already reached its daily limit. Your bundle is now active."

internal const val ACTION_TX_CREATED = "com.bingwa.mobile.TX_CREATED"
