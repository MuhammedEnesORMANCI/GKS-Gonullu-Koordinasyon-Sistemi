-- RAPOR_7 madde 4: kayıtlı aktif kullanıcının operasyona doğrudan (davetsiz) katılımcı olarak eklenmesi.
-- Eklenen kişiye uygulama içi + normal öncelikli telefon bildirimi: yeni bildirim türü.
ALTER TABLE notifications.notifications DROP CONSTRAINT ck_notifications_kind;
ALTER TABLE notifications.notifications ADD CONSTRAINT ck_notifications_kind CHECK (kind IN ('ALERT_SENT',
    'ALERT_CANCELLED', 'ALERT_CLOSED', 'TASK_ASSIGNED', 'TASK_UPDATED', 'TASK_CANCELLED', 'TASK_CAPACITY_FULL',
    'TASK_ASSIGNMENT_REMOVED', 'MEETING_POINT_UPDATED', 'MEETING_POINT_CANCELLED', 'GROUP_MEMBER_ADDED', 'ANNOUNCEMENT',
    'OPERATION_INVITE', 'OPERATION_CLOSE_REMINDER', 'LOCATION_STOP', 'SESSION_REVOKED', 'MESSAGE_POSTED',
    'OPERATION_PARTICIPANT_ADDED'));
