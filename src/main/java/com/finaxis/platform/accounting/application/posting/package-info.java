/**
 * Public accounting posting API. Product modules express posting intent and financial facts through
 * these contracts and never touch accounting persistence, journals, or generated jOOQ accounting
 * tables.
 */
@org.springframework.modulith.NamedInterface("posting")
package com.finaxis.platform.accounting.application.posting;
