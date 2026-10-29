"""Publish main pushes as snapshots; stable releases still require version tags."""


def requested_channel(event, ref_type, ref_name, publish_snapshot):
    if event == "push" and ref_type == "tag" and ref_name.startswith("v"):
        return "release"
    if event == "push" and ref_type == "branch" and ref_name == "main":
        return "snapshot"
    if (event == "workflow_dispatch" and ref_type == "branch"
            and ref_name == "main" and publish_snapshot == "true"):
        return "snapshot"
    return None
