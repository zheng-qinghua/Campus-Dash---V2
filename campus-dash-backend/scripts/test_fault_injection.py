"""故障注入器协议回归：不连接数据库、不启动服务。"""
import threading
import unittest

from fault_injection import LostCommitAckProxy


class LostCommitAckProxyTest(unittest.TestCase):
    def setUp(self):
        # 只测试包识别，不调用会开启监听端口的构造器。
        self.proxy = LostCommitAckProxy.__new__(LostCommitAckProxy)
        self.proxy.lock = threading.Lock()
        self.proxy.dropped = threading.Event()
        self.proxy.target_biz = None
        self.proxy.generation = 0
        self.proxy.observed = []
        self.proxy.arm('settle:12')
        self.state = self.new_connection()

    def new_connection(self, attributes=True):
        state = {'handshake': False, 'query_attributes': False, 'matched': None,
                 'insert_pending': None, 'commit_pending': None}
        capabilities = LostCommitAckProxy.QUERY_ATTRIBUTES if attributes else 0
        self.proxy.observe_packet(state, capabilities.to_bytes(4, 'little') + bytes(28), True)
        return state

    def query(self, sql, state=None):
        state = state or self.state
        prefix = b'\x03\x00\x01' if state['query_attributes'] else b'\x03'
        return self.proxy.observe_packet(state, prefix + sql.encode(), True)

    def response(self, body=b'\x00', state=None):
        return self.proxy.observe_packet(state or self.state, body, False)

    def insert(self, biz='settle:12', response=b'\x00', state=None):
        self.query("INSERT INTO wallet_ledger (biz_no) VALUES ('" + biz + "')", state)
        return self.response(response, state)

    def test_query_attributes_commit_ok_is_dropped(self):
        self.insert()
        self.query('COMMIT')
        self.assertTrue(self.response())
        self.assertEqual([{'event': 'COMMIT_OK_DROPPED', 'bizNo': 'settle:12'}], self.proxy.observed)

    def test_legacy_com_query_still_supported(self):
        self.state = self.new_connection(attributes=False)
        self.insert()
        self.query('commit;')
        self.assertTrue(self.response())

    def test_prefix_collision_does_not_arm_commit(self):
        self.insert('settle:123')
        self.query('COMMIT')
        self.assertFalse(self.response())
        self.assertFalse(self.proxy.dropped.is_set())

    def test_failed_ledger_insert_does_not_arm_commit(self):
        self.insert(response=b'\xff')
        self.query('COMMIT')
        self.assertFalse(self.response())

    def test_rollback_clears_target_transaction(self):
        self.insert()
        self.query('ROLLBACK')
        self.assertFalse(self.response())
        self.insert('unrelated')
        self.query('COMMIT')
        self.assertFalse(self.response())

    def test_commit_error_is_forwarded_and_does_not_poison_next_transaction(self):
        self.insert()
        self.query('COMMIT')
        self.assertFalse(self.response(b'\xff'))
        self.insert('unrelated')
        self.query('COMMIT')
        self.assertFalse(self.response())

    def test_drop_is_one_shot_across_connections(self):
        other = self.new_connection()
        self.insert()
        self.insert(state=other)
        self.query('COMMIT')
        self.query('COMMIT', other)
        self.assertTrue(self.response())
        self.assertFalse(self.response(state=other))

    def test_rearm_cannot_drop_old_target_commit(self):
        self.insert()
        self.proxy.arm('settle:99')
        self.query('COMMIT')
        self.assertFalse(self.response())
        self.insert('settle:99')
        self.query('COMMIT')
        self.assertTrue(self.response())

    def test_quoted_table_and_whitespace_are_recognized(self):
        self.query("INSERT \n INTO `isolated`.`wallet_ledger` (biz_no) VALUES ('settle:12')")
        self.response()
        self.query('COMMIT')
        self.assertTrue(self.response())

    def test_server_prepare_is_reported_without_guessing_transaction(self):
        self.proxy.observe_packet(self.state, b'\x16INSERT INTO wallet_ledger (biz_no) VALUES (?)', True)
        self.query('COMMIT')
        self.assertFalse(self.response())
        self.assertEqual([{'event': 'UNSUPPORTED_SERVER_PREPARE'}], self.proxy.observed)


if __name__ == '__main__':
    unittest.main()
