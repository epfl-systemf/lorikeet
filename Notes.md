Student A
Assignment "find"

cs-214.epfl.ch/lorikeet-feedback/find/A-random_number/1
1:
Before: if ready then false else !ready
After: !ready && !ready
Next Link -> 2.

cs-214.epfl.ch/lorikeet-feedback/find/A-random_number/2
2:
Before: if ready then true else false
After: ready

cs-214.epfl.ch/lorikeet-feedback/find/A-random_number/3
3:
var x = 1
Description: Do not use variables


Q: Eye tracking/how long did someone read the feedback part?
Q: Did some open this site? -> Did someone read the feedback part?
Q: Did someone download the feedback?

Student submission -> analyze it -> generate fedback -> show it to students

What needs to be done:
- Nice feedback generation HTML (+ JS) [raw report + diff -> HTML]
- Telemetry based on that [Add telemetry hooks to the website/server]
- Deploy it on some server (Do we have a server to deploy this? Do we have logs from moodle about downloads?)
