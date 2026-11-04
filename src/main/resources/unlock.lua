-- 原子解锁脚本：校验锁归属+删除锁
-- KEYS[1] = 锁的key (如 lock:order:1016)
-- ARGV[1] = 当前线程唯一标识 (UUID+线程ID)
-- 1. 获取锁中存储的线程ID
local lockId = redis.call('GET', KEYS[1])
-- 2. 判断：是否是当前线程的锁？
if lockId == ARGV[1] then
    -- 3. 如果是，原子删除锁 -> 返回1 (或返回删除结果)
    return redis.call('DEL', KEYS[1])
end
-- 4. 如果不是，不执行操作 -> 返回0
return 0