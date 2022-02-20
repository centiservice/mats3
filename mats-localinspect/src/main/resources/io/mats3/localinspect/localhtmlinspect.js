function mats_messages_summary(event) {
    let button = event.target;
    let parentSpan = button.closest("span")
    parentSpan.querySelector(".mats_msgs_details").classList.add("mats_noshow");
    parentSpan.querySelector(".mats_msgs_details_btn").classList.remove("mats_msgs_summary_or_details_btn_active");
    parentSpan.querySelector(".mats_msgs_summary").classList.remove("mats_noshow");
    parentSpan.querySelector(".mats_msgs_summary_btn").classList.add("mats_msgs_summary_or_details_btn_active");

}
function mats_messages_details(event) {
    let button = event.target;
    let parentSpan = button.closest("span")
    parentSpan.querySelector(".mats_msgs_details").classList.remove("mats_noshow");
    parentSpan.querySelector(".mats_msgs_details_btn").classList.add("mats_msgs_summary_or_details_btn_active");
    parentSpan.querySelector(".mats_msgs_summary").classList.add("mats_noshow");
    parentSpan.querySelector(".mats_msgs_summary_btn").classList.remove("mats_msgs_summary_or_details_btn_active");
}