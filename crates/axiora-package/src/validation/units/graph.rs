use super::*;

pub(super) fn validate_activity_graph(
    validator: &mut Validator,
    file: &str,
    entry: &str,
    activities: &BTreeMap<String, Activity>,
) {
    if !activities.contains_key(entry) {
        validator.error("AXC203", file, "$.entry", "Entry activity does not exist.");
        return;
    }
    let mut ordered_ids = activities.keys().collect::<Vec<_>>();
    ordered_ids.sort_by_key(|id| activities[*id].order);
    for id in &ordered_ids {
        let activity = &activities[*id];
        for target in &activity.targets {
            if !activities.contains_key(target) {
                validator.error(
                    "AXC203",
                    file,
                    &format!("$.activities.{id}"),
                    format!("Activity target '{target}' does not exist."),
                );
            }
        }
    }
    fn reach(id: &str, graph: &BTreeMap<String, Activity>, reachable: &mut HashSet<String>) {
        if !reachable.insert(id.to_owned()) {
            return;
        }
        if let Some(activity) = graph.get(id) {
            for target in &activity.targets {
                if graph.contains_key(target) {
                    reach(target, graph, reachable);
                }
            }
        }
    }
    let mut reachable = HashSet::new();
    reach(entry, activities, &mut reachable);
    for id in &ordered_ids {
        if !reachable.contains(*id) {
            validator.error(
                "AXC203",
                file,
                &format!("$.activities.{id}"),
                "Activity is unreachable from entry.",
            );
        }
    }
    fn dfs(
        id: &str,
        graph: &BTreeMap<String, Activity>,
        visiting: &mut HashSet<String>,
        done: &mut HashSet<String>,
        cycles: &mut Vec<String>,
    ) {
        if visiting.contains(id) {
            cycles.push(id.to_owned());
            return;
        }
        if !done.insert(id.to_owned()) {
            return;
        }
        visiting.insert(id.to_owned());
        if let Some(activity) = graph.get(id) {
            for target in &activity.targets {
                if graph.contains_key(target) {
                    dfs(target, graph, visiting, done, cycles);
                }
            }
        }
        visiting.remove(id);
    }
    let mut visiting = HashSet::new();
    let mut done = HashSet::new();
    let mut cycles = Vec::new();
    for id in &ordered_ids {
        dfs(id, activities, &mut visiting, &mut done, &mut cycles);
    }
    for id in cycles {
        validator.error(
            "AXC203",
            file,
            &format!("$.activities.{id}"),
            "Activity graph contains a cycle.",
        );
    }
    fn terminates(
        id: &str,
        graph: &BTreeMap<String, Activity>,
        memo: &mut BTreeMap<String, bool>,
        checking: &mut HashSet<String>,
    ) -> bool {
        if let Some(value) = memo.get(id) {
            return *value;
        }
        if !checking.insert(id.to_owned()) {
            return false;
        }
        let Some(activity) = graph.get(id) else {
            checking.remove(id);
            return false;
        };
        let result = if activity.kind == "end" {
            true
        } else {
            !activity.targets.is_empty()
                && activity
                    .targets
                    .iter()
                    .all(|target| terminates(target, graph, memo, checking))
        };
        checking.remove(id);
        memo.insert(id.to_owned(), result);
        result
    }
    let mut memo = BTreeMap::new();
    let mut checking = HashSet::new();
    for id in &ordered_ids {
        if !terminates(id, activities, &mut memo, &mut checking) {
            validator.error(
                "AXC203",
                file,
                &format!("$.activities.{id}"),
                "Activity has a path that does not terminate in an end activity.",
            );
        }
    }
}
