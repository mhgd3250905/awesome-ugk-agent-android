# Keep Android lifecycle entry points that are referenced from the merged
# manifest rather than from host code.
-keep class com.ugk.pi.task.runtime.AgentTaskAlarmReceiver { <init>(); *; }
-keep class com.ugk.pi.task.runtime.AgentTaskJobService { <init>(); *; }
