import type { TreeNote } from '../../utils/knowledge-tree'
export * from '../../utils/knowledge-tree'

export function exampleNote(): TreeNote {
  return {
    version: 1,
    title: '线程池，任务到底怎么跑？',
    status: 'REVIEW',
    nodes: [
      {
        id: 'flow', title: '先理解任务的执行顺序',
        body: '理解线程池的关键，是把 **创建线程、进入队列、拒绝任务** 放到同一条执行路径里。',
        children: [
          { id: 'core', title: '线程数小于 corePoolSize', body: '提交新任务时，优先创建核心线程来执行任务，即使当前存在空闲线程。这里的“核心”指的是线程池维护的线程数量规则。', children: [] },
          { id: 'queue', title: '达到核心线程数，先尝试入队', body: '当工作线程数达到 `corePoolSize`，线程池会先尝试将任务加入工作队列。\n\n**队列还能接收任务时，不会因为线程数尚未达到 maximumPoolSize 就继续创建线程。**', children: [
            { id: 'unbounded', title: '为什么无界队列会让 maximumPoolSize 很难生效？', body: '因为任务通常都能进入队列，执行流程就很少走到“创建非核心线程”这一步。任务持续积压还可能耗尽内存，因此无界队列也需要谨慎使用。', children: [] },
          ] },
          { id: 'max', title: '队列已满，再尝试增加线程', body: '入队失败后，如果工作线程数还小于 `maximumPoolSize`，就创建新线程执行任务；达到上限后，交给拒绝策略处理。', children: [] },
        ],
      },
      {
        id: 'parameters', title: '把参数放回具体场景',
        body: '假设任务执行较慢，且线程池刚创建、尚未预启动核心线程。下面观察连续提交任务的过程。',
        children: [
          { id: 'example', title: '2 个核心线程 + 容量为 2 的队列', body: '```java\nnew ThreadPoolExecutor(\n    2, 4, 60, TimeUnit.SECONDS,\n    new ArrayBlockingQueue<>(2),\n    new ThreadPoolExecutor.AbortPolicy()\n);\n```\n\n假设提交期间前面的任务均未完成：\n\n| 任务 | 处理方式 |\n| --- | --- |\n| 第 1、2 个 | 创建线程执行 |\n| 第 3、4 个 | 进入队列等待 |\n| 第 5、6 个 | 创建额外线程执行 |\n| 第 7 个 | 触发拒绝策略 |', children: [] },
          { id: 'order', title: '后提交的任务可能先执行', body: '第 5 个任务可能由新线程直接执行，而第 3、4 个任务仍然在队列里等待。所以，**队列中的 FIFO 顺序不等于整个线程池的任务开始顺序**。', children: [] },
        ],
      },
      {
        id: 'rejection', title: '拒绝策略决定最后怎么处理', body: '',
        children: [
          { id: 'abort', title: 'AbortPolicy：抛出异常', body: '默认策略。抛出 `RejectedExecutionException`，调用方可以明确感知本次提交失败。', children: [] },
          { id: 'caller', title: 'CallerRunsPolicy：让提交方执行', body: '在线程池未关闭时，由提交任务的线程执行任务，降低提交速度。但如果提交方是请求处理线程，它也会被任务占用。', children: [] },
        ],
      },
      { id: 'review', title: '留给下一次复习的问题', body: '- 如果把队列容量改成 10，maximumPoolSize 会在什么时候起作用？\n- 为什么线程池的参数必须结合任务耗时和请求量来设置？', children: [] },
    ],
  }
}
