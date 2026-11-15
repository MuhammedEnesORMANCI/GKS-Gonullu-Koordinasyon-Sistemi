import { describe, expect, it } from 'vitest';
import { MOCK_IDS } from '../mock/store';
import { mockGroupService, mockOperationService, mockTaskService, setMockPersona } from '../mock/services';

describe('mock servisler sözleşme kurallarını taklit eder', () => {
  it('operasyon: geçersiz geçiş INVALID_STATE_TRANSITION', async () => {
    setMockPersona('KOR');
    const svc = mockOperationService();
    await expect(svc.changeState(MOCK_IDS.op1, { targetState: 'ACTIVE' })).rejects.toMatchObject({
      code: 'INVALID_STATE_TRANSITION',
      extensions: { currentState: 'ACTIVE', requestedState: 'ACTIVE' },
    });
    await expect(svc.changeState(MOCK_IDS.op2, { targetState: 'ACTIVE' })).resolves.toMatchObject({ state: 'ACTIVE' });
  });

  it('grup: lider çıkarılamaz, katılımcı olmayan eklenemez, mükerrer üye', async () => {
    setMockPersona('KOR');
    const svc = mockGroupService();
    await expect(svc.removeMember(MOCK_IDS.group1, MOCK_IDS.volunteer(1))).rejects.toMatchObject({ code: 'LEADER_REQUIRED' });
    await expect(svc.addMember(MOCK_IDS.group1, MOCK_IDS.volunteer(8))).rejects.toMatchObject({ code: 'NOT_A_PARTICIPANT' });
    await expect(svc.addMember(MOCK_IDS.group1, MOCK_IDS.volunteer(2))).rejects.toMatchObject({
      code: 'ALREADY_EXISTS',
      extensions: { conflict: 'ALREADY_MEMBER' },
    });
  });

  it('grup: ADM KOR olmadan üye ekler (ADR-015); KOR olmayan TEK → FORBIDDEN', async () => {
    setMockPersona('ADM');
    await expect(mockGroupService().addMember(MOCK_IDS.group1, MOCK_IDS.volunteer(4))).resolves.toBeTruthy();
    setMockPersona('TEK');
    await expect(mockGroupService().addMember(MOCK_IDS.group1, MOCK_IDS.volunteer(5))).rejects.toMatchObject({ code: 'FORBIDDEN' });
  });

  it('görev: sürüm çatışması ve kapasite kabul sayısının altına inemez', async () => {
    setMockPersona('KOR');
    const svc = mockTaskService();
    await expect(svc.update(MOCK_IDS.task1, { title: 'x yz', version: 1 })).rejects.toMatchObject({
      code: 'VERSION_CONFLICT',
      extensions: { currentVersion: 2 },
    });
    await expect(svc.update(MOCK_IDS.task1, { capacity: 0, version: 2 })).rejects.toMatchObject({ code: 'CAPACITY_BELOW_ACCEPTED' });
  });

  it('görev: başlangıçsız zaman aralığı 400 timeWindow.start REQUIRED (ck_tasks_time_window)', async () => {
    setMockPersona('KOR');
    const svc = mockTaskService();
    await expect(
      svc.create(MOCK_IDS.op1, { title: 'Görev', targets: { groupIds: [MOCK_IDS.group1] }, timeWindow: { end: '2026-11-16T10:00:00Z' } }),
    ).rejects.toMatchObject({ status: 400, code: 'VALIDATION_FAILED', errors: [{ field: 'timeWindow.start', code: 'REQUIRED' }] });
    await expect(svc.update(MOCK_IDS.task2, { timeWindow: { end: '2026-11-16T10:00:00Z' }, version: 1 })).rejects.toMatchObject({
      errors: [{ field: 'timeWindow.start', code: 'REQUIRED' }],
    });
  });

  it('görev: yayın aynı Idempotency-Key ile tekrar edilince aynı sonucu döner', async () => {
    setMockPersona('KOR');
    const svc = mockTaskService();
    const first = await svc.publish(MOCK_IDS.task2, 1, 'key-x');
    const again = await svc.publish(MOCK_IDS.task2, 1, 'key-x');
    expect(first.state).toBe('PUBLISHED');
    expect(again).toEqual(first);
    await expect(svc.publish(MOCK_IDS.task2, first.version, 'key-y')).rejects.toMatchObject({ code: 'INVALID_STATE_TRANSITION' });
  });
});
